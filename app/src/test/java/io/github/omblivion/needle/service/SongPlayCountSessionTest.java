package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.TopTracksWindow;
import io.github.omblivion.needle.provider.SongPlayCountStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class SongPlayCountSessionTest {

    @Test
    public void aQualifyingSessionRecordsExactlyOnePlay() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            SongPlayCountHelper helper = new SongPlayCountHelper();
            helper.notifySongChanged(song(42L, /*durationMs=*/ 1000L), store);
            helper.notifyPlayStateChanged(true);
            assertTrue(helper.commitIfQualified(store, /*elapsedMs=*/ 600L));

            assertEquals(1, totalCount(store, 42L));
        }
    }

    @Test
    public void committingTheSameSessionTwiceDoesNotDoubleCount() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            SongPlayCountHelper helper = new SongPlayCountHelper();
            helper.notifySongChanged(song(42L, 1000L), store);
            helper.notifyPlayStateChanged(true);
            assertTrue(helper.commitIfQualified(store, 600L));
            assertFalse(helper.commitIfQualified(store, 600L));

            assertEquals(1, totalCount(store, 42L));
        }
    }

    @Test
    public void aSessionThatHasNotQualifiedRecordsNothing() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            SongPlayCountHelper helper = new SongPlayCountHelper();
            helper.notifySongChanged(song(42L, 1000L), store);
            helper.notifyPlayStateChanged(true);
            assertFalse(helper.commitIfQualified(store, 400L));

            assertEquals(0, totalCount(store, 42L));
        }
    }

    @Test
    public void aSessionPendingAcrossAResetCannotResurrectTheCount() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            SongPlayCountHelper helper = new SongPlayCountHelper();
            helper.notifySongChanged(song(42L, 1000L), store);
            helper.notifyPlayStateChanged(true);

            // Reset happens while the session is still pending.
            store.resetSong(42L);

            assertTrue(helper.commitIfQualified(store, 600L));
            assertEquals(0, totalCount(store, 42L));
        }
    }

    @Test
    public void aSessionBegunAfterAResetRecordsNormally() {
        try (SongPlayCountStore store = new SongPlayCountStore(RuntimeEnvironment.getApplication())) {
            store.resetSong(42L);

            SongPlayCountHelper helper = new SongPlayCountHelper();
            helper.notifySongChanged(song(42L, 1000L), store);
            helper.notifyPlayStateChanged(true);
            assertTrue(helper.commitIfQualified(store, 600L));

            assertEquals(1, totalCount(store, 42L));
        }
    }

    private static Song song(long id, long durationMs) {
        return new Song(id, "", -1, -1, durationMs, "", -1L, -1L, -1L, "", new ArrayList<>(0));
    }

    private static long totalCount(SongPlayCountStore store, long songId) {
        try (Cursor cursor = store.getTopPlayedResults(0, TopTracksWindow.ALL_TIME, 0L)) {
            int idIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.ID);
            int countIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.PLAY_COUNT);
            while (cursor.moveToNext()) {
                if (cursor.getLong(idIndex) == songId) return cursor.getLong(countIndex);
            }
        }
        return 0;
    }
}
