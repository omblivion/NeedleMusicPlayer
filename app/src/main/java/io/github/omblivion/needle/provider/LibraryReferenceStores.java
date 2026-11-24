package io.github.omblivion.needle.provider;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.discog.ReferencePlan;
import io.github.omblivion.needle.model.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a reference plan to every app-owned store. Each store is written in
 * its own transaction and records the operation, so a replay changes nothing
 * and an interrupted publication can be resumed safely.
 */
public final class LibraryReferenceStores {
    private LibraryReferenceStores() {}

    /**
     * Reads the keys actually stored by every app-owned store and resolves them
     * against the target catalog. Recovery uses this so a replay recomputes the
     * same plan a normal publication would have applied.
     */
    public static ReferencePlan planFor(Map<Long, Song> catalog, java.util.Set<String> deletedPaths) {
        android.content.Context context = App.getStaticContext();
        Set<ReferenceKey> stored = new java.util.HashSet<>();
        collectKeys(HistoryStore.getInstance(context).getWritableDatabase(),
                "recent_history", "song_id", "path", stored);
        collectKeys(SongPlayCountStore.getInstance(context).getWritableDatabase(),
                "play_count_stats", "song_id", "path", stored);
        collectKeys(MusicPlaybackQueueStore.getInstance(context).getWritableDatabase(),
                "playing_queue", "_id", "expected_path", stored);
        collectKeys(MusicPlaybackQueueStore.getInstance(context).getWritableDatabase(),
                "original_playing_queue", "_id", "expected_path", stored);
        collectKeys(UnplayableTrackStore.getInstance(context).getWritableDatabase(),
                "reports", "song_id", "path", stored);
        for (ReferenceKey key : PreferencesBackedSongList.allStoredKeys()) stored.add(key);
        return io.github.omblivion.needle.discog.LibraryReferenceReconciler.derive(stored, catalog, deletedPaths);
    }

    private static void collectKeys(SQLiteDatabase db, String table, String idColumn,
            String pathColumn, Set<ReferenceKey> into) {
        if (!tableExists(db, table)) return;
        try (Cursor cursor = db.query(table, null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(idColumn));
                String path = hasColumn(cursor, pathColumn)
                        ? cursor.getString(cursor.getColumnIndexOrThrow(pathColumn)) : "";
                into.add(new ReferenceKey(id, path == null ? "" : path));
            }
        }
    }

    public static void apply(String operation, ReferencePlan plan, Map<Long, Song> catalog) {
        android.content.Context context = App.getStaticContext();
        applyTo(context, operation, plan);
    }

    private static void applyTo(android.content.Context context, String operation, ReferencePlan plan) {
        relocate(HistoryStore.getInstance(context).getWritableDatabase(), operation,
                List.of("recent_history"), "song_id", "path", plan);
        relocate(SongPlayCountStore.getInstance(context).getWritableDatabase(), operation,
                List.of("play_count_stats"), "song_id", "path", plan);
        relocateEvents(SongPlayCountStore.getInstance(context).getWritableDatabase(), operation, plan);
        relocate(MusicPlaybackQueueStore.getInstance(context).getWritableDatabase(), operation,
                List.of("playing_queue", "original_playing_queue"), "_id", "expected_path", plan);
        relocate(UnplayableTrackStore.getInstance(context).getWritableDatabase(), operation,
                List.of("reports"), "song_id", "path", plan);
    }

    /**
     * Rewrites the ID of each stored key whose path now belongs to a different
     * track, and removes the rows of confirmed-deleted paths. A key whose path
     * is absent keeps its row and ID untouched.
     */
    private static void relocate(SQLiteDatabase db, String operation, List<String> tables,
            String idColumn, String pathColumn, ReferencePlan plan) {
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS library_reference_operations (operation TEXT PRIMARY KEY)");
            try (Cursor cursor = db.rawQuery(
                    "SELECT 1 FROM library_reference_operations WHERE operation=?", new String[]{operation})) {
                if (cursor.moveToFirst()) {
                    db.setTransactionSuccessful();
                    return;
                }
            }
            for (String table : tables) {
                if (!tableExists(db, table)) continue;
                List<ContentValues> rows = new ArrayList<>();
                try (Cursor cursor = db.query(table, null, null, null, null, null, "rowid")) {
                    while (cursor.moveToNext()) {
                        ContentValues row = new ContentValues();
                        android.database.DatabaseUtils.cursorRowToContentValues(cursor, row);
                        long oldId = cursor.getLong(cursor.getColumnIndexOrThrow(idColumn));
                        String path = hasColumn(cursor, pathColumn) ? cursor.getString(cursor.getColumnIndexOrThrow(pathColumn)) : "";
                        ReferenceKey key = new ReferenceKey(oldId, path == null ? "" : path);
                        if (plan.deletedKeys.contains(key)) continue;
                        ReferenceKey relocated = plan.relocations.get(key);
                        if (relocated != null) {
                            row.put(idColumn, relocated.id());
                            if (hasColumn(cursor, pathColumn)) row.put(pathColumn, relocated.path());
                        }
                        rows.add(row);
                    }
                }
                db.delete(table, null, null);
                for (ContentValues row : rows) db.insertOrThrow(table, null, row);
            }
            ContentValues marker = new ContentValues();
            marker.put("operation", operation);
            db.insertOrThrow("library_reference_operations", null, marker);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Play events and legacy weeks follow the stats row that owns the same ID. */
    private static void relocateEvents(SQLiteDatabase db, String operation, ReferencePlan plan) {
        if (plan.relocations.isEmpty() && plan.deletedKeys.isEmpty()) return;
        for (String table : List.of("play_event", "legacy_week")) {
            if (!tableExists(db, table)) continue;
            relocateIdsOnly(db, table, plan);
        }
    }

    private static void relocateIdsOnly(SQLiteDatabase db, String table, ReferencePlan plan) {
        db.beginTransaction();
        try {
            List<ContentValues> rows = new ArrayList<>();
            try (Cursor cursor = db.query(table, null, null, null, null, null, "rowid")) {
                while (cursor.moveToNext()) {
                    ContentValues row = new ContentValues();
                    android.database.DatabaseUtils.cursorRowToContentValues(cursor, row);
                    long oldId = cursor.getLong(cursor.getColumnIndexOrThrow("song_id"));
                    ReferenceKey key = new ReferenceKey(oldId, "");
                    if (containsId(plan.deletedKeys, oldId)) continue;
                    Long newId = relocatedIdFor(plan, oldId);
                    if (newId != null) row.put("song_id", newId);
                    rows.add(row);
                }
            }
            db.delete(table, null, null);
            for (ContentValues row : rows) db.insertOrThrow(table, null, row);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static boolean containsId(java.util.Set<ReferenceKey> keys, long id) {
        for (ReferenceKey key : keys) if (key.id() == id) return true;
        return false;
    }

    private static Long relocatedIdFor(ReferencePlan plan, long id) {
        for (Map.Entry<ReferenceKey, ReferenceKey> entry : plan.relocations.entrySet()) {
            if (entry.getKey().id() == id) return entry.getValue().id();
        }
        return null;
    }

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean hasColumn(Cursor cursor, String column) {
        return cursor.getColumnIndex(column) >= 0;
    }
}
