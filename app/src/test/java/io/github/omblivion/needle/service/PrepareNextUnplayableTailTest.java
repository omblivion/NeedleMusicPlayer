package io.github.omblivion.needle.service;

import static org.junit.Assert.assertNotEquals;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PrepareNextUnplayableTailTest {
    /**
     * Records the preload request instead of touching a real player.
     */
    private static class RecordingService extends MusicService {
        String preloadedUri;
        int preloadedPosition = -1;

        @Override void setNextDataSource(String uri, int position) {
            preloadedUri = uri;
            preloadedPosition = position;
        }

        @Override public void play() {
        }

        @Override void notifyChange(String what) {
        }
    }

    @Test
    public void anUnplayableTailDoesNotSpinThePreloadForever() {
        RecordingService service = new RecordingService();
        // Good head, then two unplayable tracks at the end, repeat off.
        service.openQueueForTest(new String[]{"good", "bad1", "bad2"}, 0);
        service.markUriUnplayableForTest(service.trackUriAtForTest(1));
        service.markUriUnplayableForTest(service.trackUriAtForTest(2));
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_NONE);

        // Must return instead of looping on the playback thread.
        service.prepareNextImpl();

        // With no playable candidate ahead, the queue's next position is left
        // as the queue computed it; what matters is that we came back at all.
        assertNotEquals(-1, service.preloadedPosition);
    }
}
