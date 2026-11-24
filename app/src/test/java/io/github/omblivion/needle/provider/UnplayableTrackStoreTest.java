package io.github.omblivion.needle.provider;

import static org.junit.Assert.*;

import android.content.Context;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTrackStoreTest {
    private Context context;
    private UnplayableTrackStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(UnplayableTrackStore.DATABASE_NAME);
        store = new UnplayableTrackStore(context);
    }

    public static Song song(long id, String path) {
        return new Song(id, "Broken track", 2, 2026, 200000, path,
                100, 200, 7, "An album", List.of("An artist", "Another artist"));
    }

    @Test
    public void reportSurvivesClosingAndReopeningWithoutLibraryMembership() {
        Song song = song(42, "/storage/emulated/0/Music/broken.mp3");
        store.recordFailure(song);
        store.close();
        try (UnplayableTrackStore reopened = new UnplayableTrackStore(context)) {
            assertEquals(List.of(song), reopened.getSongs());
        }
    }

    @Test
    public void repeatedFailureUpdatesOneEntry() {
        Song song = song(42, "/music/broken.mp3");
        store.recordFailure(song);
        song.title = "Updated title";
        store.recordFailure(song);
        assertEquals(1, store.getSongs().size());
        assertEquals("Updated title", store.getSongs().get(0).title);
    }

    @Test
    public void placeholderIsNotRecorded() {
        store.recordFailure(Song.EMPTY_SONG);
        assertTrue(store.getSongs().isEmpty());
    }

    @Test
    public void removalOnlyRemovesTheMatchingFileNotAReusedMediaId() {
        Song original = song(42, "/music/original.mp3");
        Song replacement = song(42, "/music/replacement.mp3");
        store.recordFailure(replacement);
        assertFalse(store.remove(original));
        assertEquals(List.of(replacement), store.getSongs());
        assertTrue(store.remove(replacement));
        assertTrue(store.getSongs().isEmpty());
    }

    @Test
    public void removingOneReportPreservesTheOthers() {
        Song first = song(42, "/music/first.mp3");
        Song second = song(43, "/music/second.mp3");
        store.recordFailure(first);
        store.recordFailure(second);
        store.remove(first);
        assertEquals(List.of(second), store.getSongs());
    }
}
