package io.github.omblivion.needle.provider;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;

import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.MusicUtil;

import java.util.ArrayList;

/**
 * Persistent reports, separate from the player's session-only skip registry.
 * Snapshots keep files discoverable even when the library cannot load them.
 */
public class UnplayableTrackStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "unplayable_tracks.db";
    public static final String CHANGED = "io.github.omblivion.needle.unplayabletrackschanged";
    private static UnplayableTrackStore instance;
    private final Context context;

    public UnplayableTrackStore(@NonNull Context context) {
        super(context, DATABASE_NAME, null, 1);
        this.context = context.getApplicationContext();
    }

    public static synchronized UnplayableTrackStore getInstance(@NonNull Context context) {
        if (instance == null) instance = new UnplayableTrackStore(context);
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reports (uri TEXT PRIMARY KEY NOT NULL, song_id INTEGER NOT NULL,"
                + " path TEXT NOT NULL, title TEXT NOT NULL, track_number INTEGER, year INTEGER,"
                + " duration INTEGER, date_added INTEGER, date_modified INTEGER, album_id INTEGER,"
                + " album_name TEXT, artists TEXT NOT NULL, failed_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial schema.
    }

    public void recordFailure(@NonNull Song song) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            recordFailureInternal(song);
            return null;
        });
    }

    private void recordFailureInternal(Song song) {
        if (song.id < 0 || song.data.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("uri", MusicUtil.getSongFileUri(song.id).toString());
        values.put("song_id", song.id);
        values.put("path", song.data);
        values.put("title", song.title);
        values.put("track_number", song.trackNumber);
        values.put("year", song.year);
        values.put("duration", song.duration);
        values.put("date_added", song.dateAdded);
        values.put("date_modified", song.dateModified);
        values.put("album_id", song.albumId);
        values.put("album_name", song.albumName);
        values.put("artists", new JSONArray(song.artistNames).toString());
        values.put("failed_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("reports", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        notifyChanged();
    }

    @NonNull
    public ArrayList<Song> getSongs() {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(this::getSongsInternal);
    }

    private ArrayList<Song> getSongsInternal() {
        ArrayList<Song> songs = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("reports",
                new String[]{"song_id", "title", "track_number", "year", "duration", "path",
                        "date_added", "date_modified", "album_id", "album_name", "artists"},
                null, null, null, null, "failed_at DESC, song_id ASC")) {
            while (cursor.moveToNext()) {
                ArrayList<String> artists = new ArrayList<>();
                try {
                    JSONArray array = new JSONArray(cursor.getString(10));
                    for (int i = 0; i < array.length(); i++) artists.add(array.getString(i));
                } catch (JSONException ignored) {
                    // Keep the file reference even if optional display metadata is damaged.
                }
                songs.add(new Song(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getInt(3), cursor.getLong(4), cursor.getString(5), cursor.getLong(6),
                        cursor.getLong(7), cursor.getLong(8), cursor.getString(9), artists));
            }
        }
        return songs;
    }

    public boolean contains(@NonNull Song song) {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> containsInternal(song));
    }

    private boolean containsInternal(Song song) {
        try (Cursor cursor = getReadableDatabase().query("reports", new String[]{"song_id"},
                "song_id = ? AND path = ?", new String[]{Long.toString(song.id), song.data},
                null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    public boolean remove(@NonNull Song song) {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> removeInternal(song));
    }

    private boolean removeInternal(Song song) {
        boolean removed = getWritableDatabase().delete("reports", "song_id = ? AND path = ?",
                new String[]{Long.toString(song.id), song.data}) > 0;
        if (removed) notifyChanged();
        return removed;
    }

    private void notifyChanged() {
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }
}
