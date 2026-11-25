package io.github.omblivion.needle.provider;

import static org.junit.Assert.*;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.omblivion.needle.App;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class ReferencePathMigrationTest {
    @org.junit.Before @After public void resetStores() {
        ReflectionHelpers.setStaticField(HistoryStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(SongPlayCountStore.class, "sInstance", null);
    }

    @Test public void historyUpgradeKeepsRowsAndStartsPathsEmpty() {
        try (SQLiteDatabase database = RuntimeEnvironment.getApplication()
                .openOrCreateDatabase("history.db", 0, null)) {
            database.execSQL("CREATE TABLE recent_history (song_id LONG NOT NULL, time_played LONG NOT NULL)");
            database.execSQL("INSERT INTO recent_history VALUES (42, 1234)");
            database.setVersion(1);
        }
        HistoryStore store = HistoryStore.getInstance(RuntimeEnvironment.getApplication());
        try (Cursor cursor = store.getReadableDatabase()
                .rawQuery("SELECT song_id,time_played,path FROM recent_history", null)) {
            assertEquals(2, store.getReadableDatabase().getVersion());
            assertTrue(cursor.moveToFirst());
            assertEquals(42, cursor.getLong(0));
            assertEquals(1234, cursor.getLong(1));
            assertEquals("", cursor.getString(2));
        }
    }

    @Test public void statisticsUpgradeKeepsCountsEventsAndWeeks() {
        try (SQLiteDatabase database = RuntimeEnvironment.getApplication()
                .openOrCreateDatabase("song_play_count.db", 0, null)) {
            database.execSQL("CREATE TABLE play_count_stats (song_id LONG PRIMARY KEY, "
                    + "total_count LONG NOT NULL DEFAULT 0, reset_version LONG NOT NULL DEFAULT 0)");
            database.execSQL("INSERT INTO play_count_stats VALUES (7, 17, 4)");
            database.setVersion(4);
        }
        SongPlayCountStore store = SongPlayCountStore.getInstance(RuntimeEnvironment.getApplication());
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT total_count,reset_version,path FROM play_count_stats WHERE song_id=7", null)) {
            assertEquals(6, store.getReadableDatabase().getVersion());
            assertTrue(cursor.moveToFirst());
            assertEquals(17, cursor.getLong(0));
            assertEquals(4, cursor.getLong(1));
            assertEquals("", cursor.getString(2));
        }
    }

    @Test public void recordPlayStoresThePath() {
        SongPlayCountStore store = SongPlayCountStore.getInstance(RuntimeEnvironment.getApplication());
        String path = io.github.omblivion.needle.model.SongSource.canonicalPath("/Music/a.mp3");
        store.recordPlay(new ReferenceKey(7, path), System.currentTimeMillis(), "occ", 0);
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                "SELECT path FROM play_count_stats WHERE song_id=7", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(path, cursor.getString(0));
        }
    }

    @Test public void versionFiveMigrationPreservesUnprovenDatedAndWeeklyRowsWithoutGuessingPaths() {
        var context = RuntimeEnvironment.getApplication();
        String path = new ReferenceKey(7, "/Music/kept.mp3").path();
        long now = System.currentTimeMillis();
        try (SQLiteDatabase db = context.openOrCreateDatabase(SongPlayCountStore.DATABASE_NAME, 0, null)) {
            db.execSQL("CREATE TABLE play_count_stats (song_id LONG PRIMARY KEY, total_count LONG NOT NULL,"
                    + " reset_version LONG NOT NULL, path TEXT NOT NULL)");
            db.execSQL("CREATE TABLE play_event (song_id LONG NOT NULL, occurred_at LONG NOT NULL,"
                    + " occurrence TEXT NOT NULL, UNIQUE(song_id, occurrence))");
            db.execSQL("CREATE TABLE legacy_week (song_id LONG NOT NULL, week_index INT NOT NULL,"
                    + " legacy_count INT NOT NULL, UNIQUE(song_id, week_index))");
            db.execSQL("INSERT INTO play_count_stats VALUES (7,17,4,?), (8,9,2,'')", new String[]{path});
            db.execSQL("INSERT INTO play_event VALUES (7,?,'known'), (8,?,'legacy')", new Object[]{now, now});
            db.execSQL("INSERT INTO legacy_week VALUES (7,2000,3), (8,2000,5)");
            db.setVersion(5);
        }
        try (SongPlayCountStore store = new SongPlayCountStore(context)) {
            assertEquals(4, store.getResetVersion(new ReferenceKey(99, path)));
            for (String table : java.util.List.of("play_event", "legacy_week")) {
                try (Cursor rows = store.getReadableDatabase().rawQuery(
                        "SELECT path FROM " + table + " ORDER BY song_id", null)) {
                    assertTrue(rows.moveToFirst());
                    assertEquals("", rows.getString(0));
                    assertTrue(rows.moveToNext());
                    assertEquals("", rows.getString(0));
                    assertFalse(rows.moveToNext());
                }
            }
        }
    }
}
