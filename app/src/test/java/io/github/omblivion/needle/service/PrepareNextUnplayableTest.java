package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PrepareNextUnplayableTest {
    /**
     * A MusicService that records the preload request instead of touching a
     * real player. `play()` and `notifyChange` need a started service.
     */
    private static class RecordingService extends MusicService {
        String preloadedUri;
        int preloadedPosition = -1;

        @Override boolean openTrackAndPrepareNextAt(int position) {
            setPositionForTest(position);
            return true;
        }

        @Override public void play() {
        }

        @Override void notifyChange(String what) {
        }

        @Override void setNextDataSourceForTest(String uri, int position) {
            preloadedUri = uri;
            preloadedPosition = position;
        }
    }

    @Test
    public void preloadSkipsAKnownUnplayableUriAndStaysConsistent() {
        RecordingService service = new RecordingService();
        service.openQueueForTest(new String[]{"a", "b", "c"}, 0);
        service.markUriUnplayableForTest(service.trackUriAtForTest(1));

        service.prepareNextImpl();

        // The preload lands on "c" and the queue's next position agrees with
        // it, so a natural completion advances to the right track.
        assertEquals(service.trackUriAtForTest(2), service.preloadedUri);
        assertEquals(2, service.preloadedPosition);
    }
}
