package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.misc.queue.StaticPlayingQueue;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class QueueRemovalServiceTest {
    @Test public void removingFinalOccurrenceSchedulesPlaybackClear() {
        ProbeService service = serviceWithOneSong();
        service.removeSong(0);
        assertEquals(-1, service.requestedPosition);
    }

    @Test public void removingAllOccurrencesSchedulesPlaybackClear() {
        ProbeService service = serviceWithOneSong();
        service.removeSongs(List.of(song()));
        assertEquals(-1, service.requestedPosition);
    }

    private ProbeService serviceWithOneSong() {
        ProbeService service = new ProbeService();
        StaticPlayingQueue queue = new StaticPlayingQueue();
        queue.openQueue(List.of(song()), 0, 0);
        ReflectionHelpers.setField(service, "playingQueue", queue);
        return service;
    }

    private Song song() {
        return new Song(42, "Track", 1, 2024, 1000, "/Music/track.mp3", 0, 0, 1,
                "Album", new ArrayList<>(List.of("Artist")));
    }

    private static class ProbeService extends MusicService {
        int requestedPosition = Integer.MIN_VALUE;
        @Override public void setPosition(int position) {
            // Observe the playback-handler command, without starting an audio device.
            requestedPosition = position;
        }
        @Override void notifyChange(String action) {
            // Notification/DB boundaries are covered elsewhere; removal logic is real.
        }
    }
}
