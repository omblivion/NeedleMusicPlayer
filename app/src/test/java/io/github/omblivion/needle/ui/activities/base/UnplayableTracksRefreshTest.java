package io.github.omblivion.needle.ui.activities.base;

import static org.junit.Assert.assertEquals;

import android.os.Looper;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;
import io.github.omblivion.needle.ui.fragments.mainactivity.library.pager.PlaylistsFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTracksRefreshTest {
    public static class TestActivity extends AbsMusicServiceActivity {}

    public static class PlaylistProbe extends PlaylistsFragment {
        int reloads;
        @Override public void onServiceConnected() {}
        @Override public void onServiceDisconnected() {}
        @Override public void reload() {reloads++;}
    }

    @Test
    public void reportChangesRefreshThePlaylistWithoutPlaybackMetadataEvents() {
        TestActivity activity = Robolectric.buildActivity(TestActivity.class).get();
        PlaylistProbe playlists = new PlaylistProbe();
        activity.addMusicServiceEventListener(playlists);
        activity.onServiceConnected();
        try {
            UnplayableTrackStore store = UnplayableTrackStore.getInstance(RuntimeEnvironment.getApplication());
            Song song = new Song(42, "Broken", 0, 0, 20000, "/music/broken.mp3",
                    0, 0, 1, "Album", List.of("Artist"));
            store.recordFailure(song);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, playlists.reloads);
            store.remove(song);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, playlists.reloads);
        } finally {
            activity.onServiceDisconnected();
        }
    }
}
