package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class TrackFailedServiceTest {
    /**
     * A MusicService that records the skip request instead of dispatching it
     * to the playback handler thread, which does not run in a unit test.
     */
    private static class ProbeService extends MusicService {
        int skipRequests;

        @Override void requestSkipOfUnplayableTrack() {
            skipRequests++;
        }
    }

    @Test
    public void aRuntimeFailureMarksTheTrackAndRequestsASkip() {
        ProbeService service = new ProbeService();
        service.openQueueForTest(new String[]{"a", "b"}, 0);

        service.onTrackFailed();

        assertTrue(service.isTrackUriUnplayable(service.trackUriAtForTest(0)));
        assertEquals(1, service.skipRequests);
    }
}
