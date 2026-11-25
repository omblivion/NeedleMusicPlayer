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
     * A MusicService whose opening fails only for the URIs in
     * {@code failingUris}, and whose skip requests are recorded instead of
     * dispatched to the playback handler thread. `play()` and `notifyChange`
     * are stubbed: both need a started service.
     */
    private static class FailingOpenService extends MusicService {
        private final HashSet<String> failingUris = new HashSet<>();
        final ArrayList<Integer> openedPositions = new ArrayList<>();
        final ArrayList<Integer> skippedToPositions = new ArrayList<>();

        void failAtPosition(int position) {
            failingUris.add(trackUriAtForTest(position));
        }

        void failAllPositions(int count) {
            for (int i = 0; i < count; i++) {
                failingUris.add(trackUriAtForTest(i));
            }
        }

        @Override OpenResult openTrackAndPrepareNextAt(int position) {
            openedPositions.add(position);
            setPositionForTest(position);
            return failingUris.contains(trackUriAtForTest(position)) ? OpenResult.PLAYER_FAILED : OpenResult.OPENED;
        }

        @Override void skipToNextPlayable(boolean skippedLast) {
            skippedToPositions.add(1);
            super.skipToNextPlayable(skippedLast);
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

        assertTrue(service.isTrackUriUnplayable(service.trackUriAtForTest(0)));
        assertEquals(1, service.openedPositions.get(service.openedPositions.size() - 1).intValue());
    }

    @Test
    public void aRunOfFailuresStopsAtTheCap() {
        FailingOpenService service = new FailingOpenService();
        service.openQueueForTest(new String[]{"a", "b", "c", "d", "e", "f", "g", "h"}, 0);
        service.failAllPositions(8);
        service.setRepeatModeForTest(MusicService.REPEAT_MODE_ALL);

        service.playSongAtImpl(0);

        assertEquals(MusicService.MAX_CONSECUTIVE_SKIPS, service.getConsecutiveSkipCount());
    }
}
