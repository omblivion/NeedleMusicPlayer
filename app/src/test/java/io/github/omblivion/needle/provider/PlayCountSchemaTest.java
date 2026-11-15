package io.github.omblivion.needle.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.TopTracksWindow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class PlayCountSchemaTest {
    private static final long ONE_HOUR = 60L * 60L * 1000L;
    private static final long ONE_DAY = 24L * ONE_HOUR;
    private static final long ONE_WEEK = 7L * ONE_DAY;

    // A fixed "now" so every window boundary is a hand-derived literal.
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void legacyV3WeeklyCountsSurviveMigrationInAllTimeAndDefaultWindow() {
        createV3Database();
        seedLegacyV3Row(42L, /*week0=*/ 5, /*week_index=*/ currentEpochWeek() - 1);

        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            assertEquals(5, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
            assertEquals(5, totalCount(store, 42L, TopTracksWindow.WEEKS_52, NOW));
        }
    }

    @Test
    public void legacyWeeklyCountOutsideTheDefaultHorizonIsOnlyInAllTime() {
        createV3Database();
        seedLegacyV3Row(42L, /*week0=*/ 4, /*week_index=*/ currentEpochWeek() - 60);

        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            assertEquals(4, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.WEEKS_52, NOW));
        }
    }

    @Test
    public void reopeningTheStoreDoesNotDoubleImportLegacyCounts() {
        createV3Database();
        seedLegacyV3Row(42L, /*week0=*/ 3, /*week_index=*/ currentEpochWeek());

        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            assertEquals(3, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
            store.reopenForTest();
            store.reopenForTest();

            assertEquals(3, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void legacyArchiveTableIsPreservedReadOnlyAfterMigration() {
        createV3Database();
        seedLegacyV3Row(42L, /*week0=*/ 5, /*week_index=*/ currentEpochWeek() - 1);

        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            // The v3 archive is never dropped or rewritten by the v4 migration.
            try (Cursor cursor = store.getReadableDatabase().rawQuery(
                    "SELECT week0 FROM song_play_count WHERE song_id=42", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(5, cursor.getInt(0));
            }
        }
    }

    @Test
    public void rankingOrdersByRealCountThenSongId() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 10L, NOW - ONE_HOUR, "a");
            record(store, 10L, NOW - ONE_HOUR, "b");
            record(store, 20L, NOW - ONE_HOUR, "c");
            record(store, 30L, NOW - ONE_HOUR, "d");
            record(store, 30L, NOW - ONE_HOUR, "e");

            assertEquals(list(10L, 30L, 20L), topIds(store, 10, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void tiesBreakByAscendingSongId() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 7L, NOW - ONE_HOUR, "a");
            record(store, 3L, NOW - ONE_HOUR, "b");
            record(store, 5L, NOW - ONE_HOUR, "c");

            assertEquals(list(3L, 5L, 7L), topIds(store, 10, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void datedWindowsExcludePlaysOutsideTheirBoundary() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 1L, NOW - ONE_HOUR, "within24h");
            record(store, 2L, NOW - 3L * ONE_DAY, "within7d");
            record(store, 3L, NOW - 20L * ONE_DAY, "within30d");
            record(store, 4L, NOW - 200L * ONE_DAY, "old");

            assertEquals(list(1L), topIds(store, 10, TopTracksWindow.HOURS_24, NOW));
            assertEquals(list(1L, 2L), topIds(store, 10, TopTracksWindow.DAYS_7, NOW));
            assertEquals(list(1L, 2L, 3L), topIds(store, 10, TopTracksWindow.DAYS_30, NOW));
            assertEquals(list(1L, 2L, 3L, 4L), topIds(store, 10, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void duplicateOccurrenceRecordsOnlyOnce() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 42L, NOW - ONE_HOUR, "same-occurrence");
            record(store, 42L, NOW - ONE_HOUR, "same-occurrence");

            assertEquals(1, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void staleResetVersionCannotResurrectACount() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 42L, NOW - ONE_HOUR, "before");
            long versionBefore = store.getResetVersion(42L);

            store.resetSong(42L);
            long versionAfter = store.getResetVersion(42L);
            assertTrue(versionAfter > versionBefore);

            // Pending session that started before the reset: stale version ignored.
            record(store, 42L, NOW - ONE_HOUR, "pending", versionBefore);
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));

            // A session started after the reset records normally.
            record(store, 42L, NOW - ONE_HOUR, "after", versionAfter);
            assertEquals(1, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void resetSurvivesReopenAndWindowChange() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 42L, NOW - ONE_HOUR, "before");
            store.resetSong(42L);
            long version = store.getResetVersion(42L);

            store.reopenForTest();
            assertEquals(version, store.getResetVersion(42L));
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));

            // Reading another window never clears or restores counts.
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.HOURS_24, NOW));
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
        }
    }

    @Test
    public void datedEventsOlderThanRetentionLeaveAllTimeTotalsIntact() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 42L, NOW - 500L * ONE_DAY, "ancient");
            store.pruneForTest(NOW);

            // All-time totals are kept indefinitely; the aged-out play is still
            // counted there but is no longer exposed as a dated event.
            assertEquals(1, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
            // Every dated window only sees retained events.
            assertEquals(0, topIds(store, 10, TopTracksWindow.YEAR, NOW).size());
            assertEquals(0, totalCount(store, 42L, TopTracksWindow.WEEKS_52, NOW));
        }
    }

    @Test
    public void clearRemovesAllStatistics() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            record(store, 42L, NOW - ONE_HOUR, "a");
            store.clear();

            assertEquals(0, totalCount(store, 42L, TopTracksWindow.ALL_TIME, NOW));
            assertEquals(0, topIds(store, 10, TopTracksWindow.ALL_TIME, NOW).size());
        }
    }

    // ---- helpers -------------------------------------------------------

    private static void record(SongPlayCountStore store, long songId, long timestamp, String occurrence) {
        store.recordPlay(songId, timestamp, occurrence, store.getResetVersion(songId));
    }

    private static void record(SongPlayCountStore store, long songId, long timestamp, String occurrence,
                               long resetVersion) {
        store.recordPlay(songId, timestamp, occurrence, resetVersion);
    }

    private static void seedLegacyV3Row(long songId, int week0, int weekIndex) {
        withV3Database(db -> db.execSQL(
                "INSERT INTO song_play_count (song_id, week_index, week0, play_count_score) "
                        + "VALUES (?, ?, ?, ?)",
                new Object[] {songId, weekIndex, week0, 0}));
    }

    /** Creates a genuine v3 database file so the v4 migration path runs on open. */
    private static void createV3Database() {
        withV3Database(db -> {
            StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS song_play_count (song_id LONG UNIQUE,");
            for (int i = 0; i < 52; i++) {
                sql.append("week").append(i).append(" INT DEFAULT 0,");
            }
            sql.append("week_index INT NOT NULL, play_count_score REAL DEFAULT 0);");
            db.execSQL(sql.toString());
        });
    }

    private static void withV3Database(java.util.function.Consumer<android.database.sqlite.SQLiteDatabase> action) {
        android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                RuntimeEnvironment.getApplication().getDatabasePath(SongPlayCountStore.DATABASE_NAME), null);
        try {
            db.setVersion(3);
            action.accept(db);
        } finally {
            db.close();
        }
    }

    private static int currentEpochWeek() {
        return (int) (NOW / ONE_WEEK);
    }

    private static long totalCount(SongPlayCountStore store, long songId, TopTracksWindow window, long now) {
        try (Cursor cursor = store.getTopPlayedResults(0, window, now)) {
            int idIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.ID);
            int countIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.PLAY_COUNT);
            while (cursor.moveToNext()) {
                if (cursor.getLong(idIndex) == songId) return cursor.getLong(countIndex);
            }
        }
        return 0;
    }

    private static List<Long> topIds(SongPlayCountStore store, int limit, TopTracksWindow window, long now) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = store.getTopPlayedResults(limit, window, now)) {
            int idIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.ID);
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idIndex));
            }
        }
        return ids;
    }

    private static List<Long> list(Long... values) {
        List<Long> list = new ArrayList<>();
        for (Long value : values) list.add(value);
        return list;
    }
}
