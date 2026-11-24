/*
* Copyright (C) 2014 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package io.github.omblivion.needle.provider;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import android.provider.MediaStore.Audio.AudioColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author Andrew Neal, modified by Karim Abou Zeid
 *
 * This keeps track of the music playback and history state of the playback service
 */
public class MusicPlaybackQueueStore extends SQLiteOpenHelper {
    @Nullable
    private static MusicPlaybackQueueStore sInstance = null;
    private static final String DATABASE_NAME = "music_playback_state.db";
    private static final String PLAYING_QUEUE_TABLE_NAME = "playing_queue";
    private static final String ORIGINAL_PLAYING_QUEUE_TABLE_NAME = "original_playing_queue";
    private static final int VERSION = 8;

    /**
     * Constructor of <code>MusicPlaybackState</code>
     *
     * @param context The {@link Context} to use
     */
    private MusicPlaybackQueueStore(final Context context) {
        super(context, DATABASE_NAME, null, VERSION);
    }

    @Override
    public void onCreate(@NonNull final SQLiteDatabase db) {
        createTable(db, PLAYING_QUEUE_TABLE_NAME);
        createTable(db, ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
    }

    private static void createTable(@NonNull final SQLiteDatabase db, @NonNull final String tableName) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " +
                tableName +
                " (" +
                BaseColumns._ID + " LONG NOT NULL, " +
                MusicPlaybackColumns.INDEX_IN_QUEUE + " INT NOT NULL, " +
                "expected_path TEXT NOT NULL DEFAULT '', expected_uri TEXT NOT NULL DEFAULT ''" +
                ");");
    }

    @Override
    public void onUpgrade(@NonNull final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        migrateDB(db, oldVersion, newVersion);
    }

    @Override
    public void onDowngrade(@NonNull final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        migrateDB(db, oldVersion, newVersion);
    }

    private void migrateDB(@NonNull final SQLiteDatabase dbase, final int oldVersion, final int newVersion) {
        final List<String> tableNames = List.of(PLAYING_QUEUE_TABLE_NAME, ORIGINAL_PLAYING_QUEUE_TABLE_NAME);
        if (oldVersion == 7 && newVersion == 8) {
            for (String table : tableNames) {
                dbase.execSQL("ALTER TABLE " + table + " ADD COLUMN expected_path TEXT NOT NULL DEFAULT ''");
                dbase.execSQL("ALTER TABLE " + table + " ADD COLUMN expected_uri TEXT NOT NULL DEFAULT ''");
            }
            return;
        }
        final Consumer<SQLiteDatabase> migrateResetAll = (db) -> {
            for (final String tableName : tableNames) {
                db.execSQL("DROP TABLE IF EXISTS " + tableName);
            }
            onCreate(db);
        };
        final Consumer<SQLiteDatabase> migrateFromV6ToV7 = (db) -> {
            for (final String tableName : tableNames) {
                // From v6 to v7, only unused columns are dropped
                // Create a temp table, copy the data over and rename it
                // Cannot use 'ALTER TABLE ... DROP COLUMN ...' due to SQLite limitation
                final String tempName = tableName + "_V7";
                createTable(db, tempName);
                dbase.execSQL(String.format("INSERT INTO %s (_id, index_in_queue) SELECT %s, %s FROM %s;",
                        tempName, BaseColumns._ID, MusicPlaybackColumns.INDEX_IN_QUEUE, tableName));
                dbase.execSQL(String.format("DROP TABLE %s;", tableName));
                dbase.execSQL(String.format("ALTER TABLE %s RENAME TO %s", tempName, tableName));
            }
        };
        final Consumer<SQLiteDatabase> migrateUnsupported = (db) -> {
            final String message = String.format("Unsupported migration version %s -> %s of database %s", oldVersion, newVersion, DATABASE_NAME);
            throw new IllegalStateException(message);
        };

        if (oldVersion < newVersion)
        {
            // Upgrade path
            switch (oldVersion) {
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                    migrateResetAll.accept(dbase);
                    break;

                case 6:
                    migrateFromV6ToV7.accept(dbase);
                    break;

                default:
                    migrateUnsupported.accept(dbase);
                    break;
            }
        } else {
            // Downgrade path - downgrading is often impossible
            migrateResetAll.accept(dbase);
        }
    }

    /**
     * @param context The {@link Context} to use
     * @return A new instance of this class.
     */
    @NonNull
    public static synchronized MusicPlaybackQueueStore getInstance(@NonNull final Context context) {
        if (sInstance == null) {
            sInstance = new MusicPlaybackQueueStore(context.getApplicationContext());
        }
        return sInstance;
    }

    public void saveQueues(@NonNull final ArrayList<IndexedSong> playingQueue, @NonNull final ArrayList<IndexedSong> originalPlayingQueue) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            saveQueuesInternal(playingQueue, originalPlayingQueue);
            return null;
        });
    }

    private void saveQueuesInternal(ArrayList<IndexedSong> playingQueue, ArrayList<IndexedSong> originalPlayingQueue) {
        final SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            saveQueue(database, PLAYING_QUEUE_TABLE_NAME, playingQueue);
            saveQueue(database, ORIGINAL_PLAYING_QUEUE_TABLE_NAME, originalPlayingQueue);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Clears the existing database and saves the queue into the db so that when the
     * app is restarted, the tracks you were listening to is restored
     *
     * @param queue the queue to save
     */
    private void saveQueue(@NonNull final SQLiteDatabase database, final String tableName,
                           @NonNull final ArrayList<IndexedSong> queue) {
        database.delete(tableName, null, null);
        for (IndexedSong song : queue) {
            ContentValues values = new ContentValues();
            values.put(BaseColumns._ID, song.id);
            values.put(MusicPlaybackColumns.INDEX_IN_QUEUE, song.index);
            values.put("expected_path", song.data);
            values.put("expected_uri", song.contentUri);
            database.insertOrThrow(tableName, null, values);
        }
    }

    @NonNull
    public ArrayList<IndexedSong> getSavedPlayingQueue() {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> getQueue(PLAYING_QUEUE_TABLE_NAME));
    }

    @NonNull
    public ArrayList<IndexedSong> getSavedOriginalPlayingQueue() {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> getQueue(ORIGINAL_PLAYING_QUEUE_TABLE_NAME));
    }


    @NonNull
    private ArrayList<IndexedSong> getQueue(@NonNull final String tableName) {
        Map<Long, Song> catalog = Discography.getInstance().getAllSongsById();
        ArrayList<IndexedSong> queue = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(tableName,
                new String[]{BaseColumns._ID, MusicPlaybackColumns.INDEX_IN_QUEUE, "expected_path", "expected_uri"},
                null, null, null, null, "rowid")) {
            while (cursor.moveToNext()) {
                Song song = catalog.get(cursor.getLong(0));
                String path = cursor.getString(2);
                String uri = cursor.getString(3);
                if (song == null) {
                    // Keep the stored identity so a later refresh can still
                    // resolve the entry instead of dropping it.
                    song = new Song(cursor.getLong(0), "", 0, 0, 0, path, 0, 0, 0, "", new ArrayList<>());
                    song.contentUri = uri;
                } else if ((!path.isEmpty()
                        && !SongSource.canonicalPath(path).equals(SongSource.canonicalPath(song.data)))
                        || (!uri.isEmpty() && !song.contentUri.isEmpty() && !uri.equals(song.contentUri))) {
                    // The stored reference points at a different source than the
                    // catalog now holds. Keep the stored identity so a later
                    // refresh can still resolve it instead of discarding it.
                    song = new Song(cursor.getLong(0), "", 0, 0, 0, path, 0, 0, 0, "", new ArrayList<>());
                    song.contentUri = uri;
                }
                queue.add(new IndexedSong(song, cursor.getInt(1), IndexedSong.INVALID_INDEX));
            }
        }
        // Renumber the restored entries so the indexes stay a valid permutation
        // even if a stored row was skipped.
        for (int i = 0; i < queue.size(); i++) queue.get(i).index = i;
        return queue;
    }

    public interface MusicPlaybackColumns extends AudioColumns {
        String INDEX_IN_QUEUE = "index_in_queue";
    }
}
