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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.model.TopTracksWindow;

import java.time.ZoneId;

/**
 * Tracks real play counts for individual songs, driving the most-played views.
 *
 * <p>Schema v4 replaces the old recency-weighted weekly score. Cumulative
 * all-time totals and a per-song reset version live in {@link Columns#STATS};
 * precise dated plays live in {@link Columns#EVENTS}; the recoverable weekly
 * buckets imported from v3 live in {@link Columns#LEGACY_WEEKS}. The v3 table
 * itself is kept as a read-only archive and is never re-imported on reopen.
 */
public class SongPlayCountStore extends SQLiteOpenHelper {
    @Nullable
    private static SongPlayCountStore sInstance = null;

    public static final String DATABASE_NAME = "song_play_count.db";
    private static final int VERSION = 4;

    // How many epoch weeks the default (52w) view exposes legacy buckets for.
    private static final int LEGACY_WEEKS = 52;

    private static final int ONE_WEEK_IN_MS = 1000 * 60 * 60 * 24 * 7;

    // Dated events are kept this long; all-time totals are kept indefinitely.
    private static final long EVENT_RETENTION_MS = 400L * 24L * 60L * 60L * 1000L;

    @NonNull
    private static final String WHERE_ID_EQUALS = Columns.STATS + "." + Columns.ID + "=?";

    public SongPlayCountStore(final Context context) {
        super(context, DATABASE_NAME, null, VERSION);
        // Force the migration/import path to run before any read.
        getWritableDatabase();
    }

    @Override
    public void onCreate(@NonNull final SQLiteDatabase db) {
        createStatsTable(db);
        createEventsTable(db);
        createLegacyWeeksTable(db);
    }

    @Override
    public void onUpgrade(@NonNull final SQLiteDatabase db, final int oldVersion, final int newVersion) {
        // Never call the old destructive upgrade. Add v4 tables and import the
        // recoverable weekly buckets from the v3 archive exactly once.
        createStatsTable(db);
        createEventsTable(db);
        createLegacyWeeksTable(db);
        if (oldVersion < 4) {
            importLegacyWeeks(db);
        }
    }

    @Override
    public void onDowngrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // Downgrade is not supported: leave the data intact rather than drop it.
    }

    private static void createStatsTable(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + Columns.STATS + " ("
                + Columns.ID + " LONG PRIMARY KEY, "
                + Columns.TOTAL_COUNT + " LONG NOT NULL DEFAULT 0, "
                + Columns.RESET_VERSION + " LONG NOT NULL DEFAULT 0)");
    }

    private static void createEventsTable(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + Columns.EVENTS + " ("
                + Columns.ID + " LONG NOT NULL, "
                + Columns.OCCURRED_AT + " LONG NOT NULL, "
                + Columns.OCCURRENCE + " TEXT NOT NULL, "
                + "UNIQUE(" + Columns.ID + ", " + Columns.OCCURRENCE + "))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_event_occurred ON "
                + Columns.EVENTS + "(" + Columns.OCCURRED_AT + ")");
    }

    private static void createLegacyWeeksTable(@NonNull SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + Columns.LEGACY_WEEKS + " ("
                + Columns.ID + " LONG NOT NULL, "
                + Columns.WEEK_INDEX + " INT NOT NULL, "
                + Columns.LEGACY_COUNT + " INT NOT NULL DEFAULT 0, "
                + "UNIQUE(" + Columns.ID + ", " + Columns.WEEK_INDEX + "))");
    }

    /**
     * Copies the recoverable weekly buckets from the v3 archive into the v4
     * legacy table. Cumulative totals are seeded so All time keeps every count.
     */
    private void importLegacyWeeks(@NonNull SQLiteDatabase db) {
        db.beginTransaction();
        try {
            StringBuilder sum = new StringBuilder("0");
            for (int i = 0; i < LEGACY_WEEKS; i++) {
                sum.append("+IFNULL(").append(weekColumn(i)).append(",0)");
            }
            // One row per song with any recoverable weekly count.
            try (Cursor cursor = db.rawQuery(
                    "SELECT " + Columns.LEGACY_ID + ", " + Columns.LEGACY_WEEK_INDEX + ", " + sum
                            + " AS total FROM " + Columns.V3_TABLE
                            + " WHERE " + sum + " > 0", null)) {
                while (cursor.moveToNext()) {
                    long songId = cursor.getLong(0);
                    int weekIndex = cursor.getInt(1);
                    int total = cursor.getInt(2);
                    recordLegacyBucket(db, songId, weekIndex, total);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void recordLegacyBucket(@NonNull SQLiteDatabase db, long songId, int weekIndex, int total) {
        ContentValues stats = new ContentValues(3);
        stats.put(Columns.ID, songId);
        stats.put(Columns.TOTAL_COUNT, total);
        stats.put(Columns.RESET_VERSION, 0L);
        db.insertWithOnConflict(Columns.STATS, null, stats, SQLiteDatabase.CONFLICT_IGNORE);

        ContentValues legacy = new ContentValues(3);
        legacy.put(Columns.ID, songId);
        legacy.put(Columns.WEEK_INDEX, weekIndex);
        legacy.put(Columns.LEGACY_COUNT, total);
        db.insertWithOnConflict(Columns.LEGACY_WEEKS, null, legacy, SQLiteDatabase.CONFLICT_IGNORE);
    }

    @NonNull
    private static String weekColumn(int week) {
        return "week" + week;
    }

    /**
     * @param context The {@link Context} to use
     * @return A new instance of this class.
     */
    @NonNull
    public static synchronized SongPlayCountStore getInstance(@NonNull final Context context) {
        if (sInstance == null) {
            sInstance = new SongPlayCountStore(context.getApplicationContext());
        }
        return sInstance;
    }

    /**
     * Records a qualifying play exactly once. A call carrying a reset version
     * older than the song's stored version is ignored, so a session that began
     * before a reset cannot resurrect the cleared count.
     *
     * @param songId        the played song
     * @param timestampMs   when the play qualified, in epoch milliseconds
     * @param occurrence    a unique token identifying this play occurrence
     * @param resetVersion  the song's reset version captured when tracking began
     */
    public void recordPlay(final long songId, final long timestampMs, @NonNull final String occurrence,
                           final long resetVersion) {
        if (songId == -1) {
            return;
        }

        final SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            if (resetVersion < getResetVersionInternal(database, songId)) {
                return;
            }

            ContentValues event = new ContentValues(3);
            event.put(Columns.ID, songId);
            event.put(Columns.OCCURRED_AT, timestampMs);
            event.put(Columns.OCCURRENCE, occurrence);
            long inserted = database.insertWithOnConflict(
                    Columns.EVENTS, null, event, SQLiteDatabase.CONFLICT_IGNORE);
            if (inserted == -1) {
                // Duplicate occurrence: idempotent no-op.
                return;
            }

            ContentValues stats = new ContentValues(2);
            stats.put(Columns.TOTAL_COUNT, getTotalCountInternal(database, songId) + 1);
            stats.put(Columns.RESET_VERSION, getResetVersionInternal(database, songId));
            if (database.update(Columns.STATS, stats, Columns.ID + "=?",
                    new String[] {String.valueOf(songId)}) == 0) {
                stats.put(Columns.ID, songId);
                database.insert(Columns.STATS, null, stats);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Deletes a song's dated and legacy history, zeroes its cumulative count and
     * advances its persistent reset version. Audio, playlists, recent-history
     * storage and playback are not touched.
     */
    public void resetSong(final long songId) {
        final SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            long nextVersion = getResetVersionInternal(database, songId) + 1;

            database.delete(Columns.EVENTS, Columns.ID + "=?", new String[] {String.valueOf(songId)});
            database.delete(Columns.LEGACY_WEEKS, Columns.ID + "=?", new String[] {String.valueOf(songId)});

            ContentValues stats = new ContentValues(3);
            stats.put(Columns.ID, songId);
            stats.put(Columns.TOTAL_COUNT, 0L);
            stats.put(Columns.RESET_VERSION, nextVersion);
            if (database.update(Columns.STATS, stats, Columns.ID + "=?",
                    new String[] {String.valueOf(songId)}) == 0) {
                database.insert(Columns.STATS, null, stats);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** @return the song's persistent reset version, or 0 when never reset. */
    public long getResetVersion(final long songId) {
        return getResetVersionInternal(getReadableDatabase(), songId);
    }

    private static long getResetVersionInternal(@NonNull SQLiteDatabase database, long songId) {
        try (Cursor cursor = database.query(Columns.STATS, new String[] {Columns.RESET_VERSION},
                Columns.ID + "=?", new String[] {String.valueOf(songId)}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long getTotalCountInternal(@NonNull SQLiteDatabase database, long songId) {
        try (Cursor cursor = database.query(Columns.STATS, new String[] {Columns.TOTAL_COUNT},
                Columns.ID + "=?", new String[] {String.valueOf(songId)}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    /** Removes all statistics. */
    public void clear() {
        final SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete(Columns.EVENTS, null, null);
            database.delete(Columns.LEGACY_WEEKS, null, null);
            database.delete(Columns.STATS, null, null);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Returns the top played songs for a window, ranked by real count descending
     * with the song ID ascending as the tie-break.
     *
     * @param numResults number of results to limit by; {@code <= 0} returns all
     * @param window     the rolling window to rank within
     * @param nowMillis  the reference instant for window cutoffs
     */
    public Cursor getTopPlayedResults(int numResults, @NonNull TopTracksWindow window, long nowMillis) {
        final SQLiteDatabase database = getReadableDatabase();

        if (window == TopTracksWindow.ALL_TIME) {
            return database.rawQuery("SELECT " + Columns.ID + " AS " + Columns.ID
                    + ", " + Columns.TOTAL_COUNT + " AS " + Columns.PLAY_COUNT
                    + " FROM " + Columns.STATS + " WHERE " + Columns.TOTAL_COUNT + " > 0"
                    + " ORDER BY " + Columns.TOTAL_COUNT + " DESC, " + Columns.ID + " ASC"
                    + limitClause(numResults), null);
        }

        long cutoff = window.cutoffMillis(nowMillis, ZoneId.systemDefault());
        boolean includeLegacy = window == TopTracksWindow.WEEKS_52;
        int oldestLegacyWeek = currentEpochWeek(nowMillis) - LEGACY_WEEKS + 1;

        String legacySelect = includeLegacy
                ? " UNION ALL SELECT " + Columns.ID + ", " + Columns.LEGACY_COUNT + " FROM "
                        + Columns.LEGACY_WEEKS + " WHERE " + Columns.WEEK_INDEX + " >= " + oldestLegacyWeek
                : "";

        String sql = "SELECT " + Columns.ID + ", SUM(" + Columns.PLAY_COUNT + ") AS " + Columns.PLAY_COUNT
                + " FROM ("
                + "SELECT " + Columns.ID + ", COUNT(*) AS " + Columns.PLAY_COUNT
                + " FROM " + Columns.EVENTS + " WHERE " + Columns.OCCURRED_AT + " >= " + cutoff
                + " GROUP BY " + Columns.ID
                + legacySelect
                + ") GROUP BY " + Columns.ID
                + " HAVING " + Columns.PLAY_COUNT + " > 0"
                + " ORDER BY " + Columns.PLAY_COUNT + " DESC, " + Columns.ID + " ASC"
                + limitClause(numResults);

        return database.rawQuery(sql, null);
    }

    @NonNull
    private static String limitClause(int numResults) {
        return numResults <= 0 ? "" : " LIMIT " + numResults;
    }

    private static int currentEpochWeek(long nowMillis) {
        return (int) (nowMillis / ONE_WEEK_IN_MS);
    }

    /** Test-only: closes and reopens this store's underlying database. */
    void reopenForTest() {
        close();
        getWritableDatabase();
    }

    /** Test-only: applies the dated-event retention rule at an explicit instant. */
    void pruneForTest(long nowMillis) {
        final SQLiteDatabase database = getWritableDatabase();
        database.delete(Columns.EVENTS, Columns.OCCURRED_AT + " < ?",
                new String[] {String.valueOf(nowMillis - EVENT_RETENTION_MS)});
    }

    public interface Columns {
        String ID = "song_id";
        String PLAY_COUNT = "play_count";

        // v4 tables.
        String STATS = "play_count_stats";
        String TOTAL_COUNT = "total_count";
        String RESET_VERSION = "reset_version";

        String EVENTS = "play_event";
        String OCCURRED_AT = "occurred_at";
        String OCCURRENCE = "occurrence";

        String LEGACY_WEEKS = "legacy_week";
        String WEEK_INDEX = "week_index";
        String LEGACY_COUNT = "legacy_count";

        // Read-only v3 archive.
        String V3_TABLE = "song_play_count";
        String LEGACY_ID = "song_id";
        String LEGACY_WEEK_INDEX = "week_index";
    }
}
