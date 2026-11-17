package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PlaybackSkipBoundaryTest {
    /**
     * A MusicService whose openings all fail, so the skip always hits a
     * boundary or the cap. `play()` and `notifyChange` are stubbed: both need
     * a started service.
     */
    private static class FailingOpenService extends MusicService {
        private final HashSet<String> failingUris = new HashSet<>();
        final ArrayList<Integer> openedPositions = new ArrayList<>();

        void failAllPositions(int count) {
            for (int i = 0; i < count; i++) {
                failingUris.add(trackUriAtForTest(i));
            }
        }

        @Override boolean openTrackAndPrepareNextAt(int position) {
            openedPositions.add(position);
            setPositionForTest(position);
            return !failingUris.contains(trackUriAtForTest(position));
        }

        @Override public void play() {
        }

        @Override void notifyChange(String what) {
        }
    }

    @Test
    public void brokenLastTrackWithRepeatOffStopsWithoutWrapping() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b"}, 1);
        service.failAllPositions(2);

        service.playSongAtImpl(1);

        // Counted once and stopped: no wrap to position 0, no loop.
        assertEquals(1, service.getConsecutiveSkipCount());
        assertEquals(1, service.openedPositions.size());
    }

    @Test
    public void brokenTrackWithRepeatThisStopsAfterOneAttempt() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b"}, 0);
        service.failAllPositions(2);
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_THIS);

        service.playSongAtImpl(0);

        // Reopening the same broken track would loop forever.
        assertEquals(1, service.getConsecutiveSkipCount());
        assertEquals(1, service.openedPositions.size());
        assertTrue(service.isTrackUriUnplayableForTest(service.trackUriAtForTest(0)));
    }
}
