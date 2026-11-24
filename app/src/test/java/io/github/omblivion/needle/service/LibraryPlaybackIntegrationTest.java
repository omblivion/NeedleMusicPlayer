package io.github.omblivion.needle.service;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.misc.queue.*;
import io.github.omblivion.needle.model.Song;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryPlaybackIntegrationTest {
    @Test public void committedIdChangesPreserveQueueOrderDuplicatesAndCurrentPosition() {
        Song a = song(1, "a");
        Song b = song(2, "b");
        ArrayList<IndexedSong> items = new ArrayList<>(List.of(new IndexedSong(a, 0, 0),
                new IndexedSong(b, 1, 1), new IndexedSong(a, 2, 2)));
        StaticPlayingQueue queue = new StaticPlayingQueue(items, new ArrayList<>(items), 1, 0, 0);
        queue.reconcileSources(Map.of(9L, song(9, "a"), 2L, b), Set.of(), false);
        assertEquals(1, queue.getCurrentPosition());
        assertEquals(List.of(9L, 2L, 9L), ids(queue.getPlayingQueue()));
        assertEquals(List.of(9L, 2L, 9L), ids(queue.getOriginalPlayingQueue()));
    }

    @Test public void confirmedDeletionRemovesEveryOccurrenceEvenWithMetadataSyncDisabled() {
        Song a = song(1, "a");
        Song b = song(2, "b");
        ArrayList<IndexedSong> items = new ArrayList<>(List.of(new IndexedSong(a, 0, 0),
                new IndexedSong(b, 1, 1), new IndexedSong(a, 2, 2)));
        StaticPlayingQueue queue = new StaticPlayingQueue(items, new ArrayList<>(items), 1, 0, 0);
        queue.reconcileSources(Map.of(2L, b), Set.of(1L), false);
        assertEquals(List.of(2L), ids(queue.getPlayingQueue()));
        assertEquals(0, queue.getCurrentPosition());
    }

    @Test public void unrelatedIdReuseCannotRelabelAQueueEntry() {
        Song a = song(1, "a");
        ArrayList<IndexedSong> items = new ArrayList<>(List.of(new IndexedSong(a, 0, 0)));
        StaticPlayingQueue queue = new StaticPlayingQueue(items, new ArrayList<>(items), 0, 0, 0);
        queue.reconcileSources(Map.of(1L, song(1, "unrelated")), Set.of(), true);
        assertEquals("/Music/a.mp3", queue.getPlayingQueue().get(0).data);
    }

    private static List<Long> ids(List<IndexedSong> songs) {
        ArrayList<Long> ids = new ArrayList<>();
        for (Song song : songs) ids.add(song.id);
        return ids;
    }
    private static Song song(long id, String name) {
        return new Song(id, name, 1, 2024, 1000, "/Music/" + name + ".mp3", 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
    }
}
