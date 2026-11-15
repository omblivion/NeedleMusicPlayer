package io.github.omblivion.needle.service;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaPlayer;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class GaplessErrorTest {
    @Test
    public void preloadErrorKeepsCurrentTrackPlayingAndDiscardsFailedPreload() {
        MultiPlayer player = new MultiPlayer(RuntimeEnvironment.getApplication());
        MediaPlayer current = ReflectionHelpers.getField(player, "mCurrentMediaPlayer");
        shadowOf(current).setState(ShadowMediaPlayer.State.STARTED);
        ReflectionHelpers.setField(player, "mIsInitialized", true);
        MediaPlayer next = new MediaPlayer();
        ReflectionHelpers.setField(player, "mNextMediaPlayer", next);

        assertTrue(player.onError(next, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0));
        assertSame(current, ReflectionHelpers.getField(player, "mCurrentMediaPlayer"));
        assertTrue(player.isPlaying());
        assertNull(ReflectionHelpers.getField(player, "mNextMediaPlayer"));
        player.release();
    }
}
