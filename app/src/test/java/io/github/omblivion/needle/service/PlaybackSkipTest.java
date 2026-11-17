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
public class PlaybackSkipTest {
    /**
     * A MusicService whose opening fails only for the positions in
     * {@code failingPositions}. Playback itself is stubbed out, because
     * requesting audio focus and updating the notification need a started
     * service.
     */
    private static class FailingOpenService extends MusicService {
        private final HashSet<String> failingUris = new HashSet<>();
        final ArrayList<Integer> openedPositions = new ArrayList<>();

        void failAtPosition(int position) {
            failingUris.add(trackUriAtForTest(position));
        }

        void failAllPositions(int count) {
            for (int i = 0; i < count; i++) {
                failAtPosition(i);
            }
        }

        @Override boolean openTrackAndPrepareNextAt(int position) {
            openedPositions.add(position);
            setPositionForTest(position);
            return !failingUris.contains(trackUriAtForTest(position));
        }

        @Override public void play() {
            // Requesting audio focus needs a started service.
        }

        @Override void notifyChange(String what) {
            // The notification and media session need a started service.
        }
    }

    @Test
    public void aFailingTrackIsMarkedAndPlaybackAdvancesToTheNextPosition() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b", "c"}, 0);
        service.failAtPosition(0);

        service.playSongAtImpl(0);

        assertEquals(1, service.getConsecutiveSkipCount());
        assertTrue(service.isTrackUriUnplayableForTest(service.trackUriAtForTest(0)));
        assertEquals(1, service.openedPositions.get(service.openedPositions.size() - 1).intValue());
    }

    @Test
    public void aRunOfFailuresStopsAtTheCap() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d", "e", "f", "g", "h"}, 0);
        service.failAllPositions(8);
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_NONE);

        service.playSongAtImpl(0);

        assertEquals(MusicService.MAX_CONSECUTIVE_SKIPS, service.getConsecutiveSkipCount());
    }
}
