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
            assertEquals(5, store.getReadableDatabase().getVersion());
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
}
