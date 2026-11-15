package io.github.omblivion.needle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;

import io.github.omblivion.needle.helper.menu.ResetPlayCountsHelper;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.TopTracksWindow;
import io.github.omblivion.needle.provider.SongPlayCountStore;
import io.github.omblivion.needle.service.MusicService;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class ResetPlayCountsTest {

    @Test
    public void resettingTheHeaderRowIsANoOp() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicBoolean broadcast = new AtomicBoolean(false);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                broadcast.set(true);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(MusicService.STATISTICS_CHANGED));
        try {
            ResetPlayCountsHelper.reset(context, Song.EMPTY_SONG, Runnable::run);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            // No database work and no refresh for the header/empty row.
            assertFalse(broadcast.get());
        } finally {
            context.unregisterReceiver(receiver);
        }
    }

    @Test
    public void resetClearsTheCountAndBroadcastsStatisticsChanged() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicBoolean broadcast = new AtomicBoolean(false);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                broadcast.set(true);
            }
        };
        context.registerReceiver(receiver, new IntentFilter(MusicService.STATISTICS_CHANGED));
        try (SongPlayCountStore store = new SongPlayCountStore(context)) {
            store.recordPlay(42L, System.currentTimeMillis(), "one", 0L);
            assertEquals(1, topIds(store).size());

            ResetPlayCountsHelper.reset(context, song(42L), Runnable::run);

            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(broadcast.get());
            assertEquals(0, topIds(store).size());
        } finally {
            context.unregisterReceiver(receiver);
        }
    }

    @Test
    public void resetRunsOnTheSuppliedExecutorNotInline() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicBoolean ran = new AtomicBoolean(false);
        Executor capturing = command -> ran.set(true);
        try (SongPlayCountStore store = new SongPlayCountStore(context)) {
            store.recordPlay(42L, System.currentTimeMillis(), "one", 0L);

            ResetPlayCountsHelper.reset(context, song(42L), capturing);

            // The work was handed to the executor, not done on the calling thread.
            assertTrue(ran.get());
            assertEquals(1, topIds(store).size());
        }
    }

    @Test
    public void resetActionExistsOnlyInTheTopTracksMenu() {
        Context context = RuntimeEnvironment.getApplication();
        android.view.Menu topMenu = new androidx.appcompat.view.menu.MenuBuilder(context);
        new android.view.MenuInflater(context).inflate(R.menu.menu_item_top_track, topMenu);
        assertTrue(topMenu.findItem(R.id.action_reset_play_count) != null);

        android.view.Menu ordinaryMenu = new androidx.appcompat.view.menu.MenuBuilder(context);
        new android.view.MenuInflater(context).inflate(
                R.menu.menu_item_cannot_delete_single_songs_playlist_song, ordinaryMenu);
        assertTrue(ordinaryMenu.findItem(R.id.action_reset_play_count) == null);
    }

    private static Song song(long id) {
        return new Song(id, "", -1, -1, 1000L, "", -1L, -1L, -1L, "", new ArrayList<>(0));
    }

    private static java.util.List<Long> topIds(SongPlayCountStore store) {
        java.util.List<Long> ids = new ArrayList<>();
        try (Cursor cursor = store.getTopPlayedResults(0, TopTracksWindow.ALL_TIME, 0L)) {
            int idIndex = cursor.getColumnIndexOrThrow(SongPlayCountStore.Columns.ID);
            while (cursor.moveToNext()) ids.add(cursor.getLong(idIndex));
        }
        return ids;
    }
}
