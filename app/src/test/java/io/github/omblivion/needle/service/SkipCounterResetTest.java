package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class SkipCounterResetTest {
    /**
     * A MusicService with a controllable reported progress, so the "has this
     * track really been heard?" decision can be exercised directly.
     */
    private static class ProbeService extends MusicService {
        int reportedProgressMillis = -1;

        @Override public int getSongProgressMillis() {
            return reportedProgressMillis;
        }

        @Override OpenResult openTrackAndPrepareNextAt(int position) {
            resetConsecutiveSkipsIfPlayed();
            setPositionForTest(position);
            return OpenResult.OPENED;
        }

        @Override public void play() {
        }

        @Override void notifyChange(String what) {
        }
    }

    @Test
    public void aRunOfSkipsAccumulatesWhileNothingIsHeard() {
        ProbeService service = new ProbeService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d"}, 0);

        for (int i = 0; i < 4; i++) {
            service.skipToNextPlayable(true);
        }

        assertEquals(4, service.getConsecutiveSkipCount());
    }

    @Test
    public void realPlaybackClearsTheRunOfSkips() {
        ProbeService service = new ProbeService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d"}, 0);
        for (int i = 0; i < 4; i++) {
            service.skipToNextPlayable(true);
        }

        service.reportedProgressMillis = MusicService.SKIP_RESET_PROGRESS_MS;
        service.openTrackAndPrepareNextAt(0);

        assertEquals(0, service.getConsecutiveSkipCount());
    }

    @Test
    public void aFailedTrackThatNeverPlayedKeepsTheRun() {
        ProbeService service = new ProbeService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d"}, 0);
        for (int i = 0; i < 3; i++) {
            service.skipToNextPlayable(true);
        }

        service.reportedProgressMillis = -1;
        service.openTrackAndPrepareNextAt(0);

        assertEquals(3, service.getConsecutiveSkipCount());
    }
}
