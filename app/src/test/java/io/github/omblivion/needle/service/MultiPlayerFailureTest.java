package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaPlayer;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.service.playback.Playback;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class MultiPlayerFailureTest {
    private static class RecordingCallbacks implements Playback.PlaybackCallbacks {
        int failedCount;
        int wentToNextCount;
        int endedCount;

        @Override public void onTrackWentToNext(long sourceToken) { wentToNextCount++; }
        @Override public void onTrackEnded() { endedCount++; }
        @Override public void onTrackFailed() { failedCount++; }
    }

    @Test
    public void currentPlayerErrorReportsFailureSuppressesCompletionAndDiscardsPreload() {
        MultiPlayer player = new MultiPlayer(RuntimeEnvironment.getApplication());
        RecordingCallbacks callbacks = new RecordingCallbacks();
        player.setCallbacks(callbacks);

        MediaPlayer broken = ReflectionHelpers.getField(player, "mCurrentMediaPlayer");
        shadowOf(broken).setState(ShadowMediaPlayer.State.STARTED);
        ReflectionHelpers.setField(player, "mIsInitialized", true);
        ReflectionHelpers.setField(player, "mNextMediaPlayer", new MediaPlayer());

        assertTrue(player.onError(broken, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0));
        assertEquals(1, callbacks.failedCount);
        assertEquals(0, callbacks.endedCount);
        assertEquals(0, callbacks.wentToNextCount);
        assertNull(ReflectionHelpers.getField(player, "mNextMediaPlayer"));
        assertNotSame(broken, ReflectionHelpers.getField(player, "mCurrentMediaPlayer"));
        player.release();
    }
}
