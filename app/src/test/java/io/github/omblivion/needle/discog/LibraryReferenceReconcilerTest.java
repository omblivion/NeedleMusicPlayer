package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.provider.ReferenceKey;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryReferenceReconcilerTest {
    @Test public void storedPathFollowsTheTrackToItsNewId() {
        ReferenceKey key = key(1, "/Music/a.mp3");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(key), map(song(9, "a")), Set.of());
        assertEquals(key.withId(9), plan.relocations.get(key));
        assertTrue(plan.deletedKeys.isEmpty());
    }

    @Test public void recycledIdDoesNotClaimAnotherTracksReference() {
        ReferenceKey key = key(1, "/Music/a.mp3");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(key), map(song(1, "unrelated")), Set.of());
        assertTrue(plan.relocations.isEmpty());
        assertTrue(plan.deletedKeys.isEmpty());
    }

    @Test public void twoWaySwapResolvesEachPathToItsOwnTrack() {
        ReferenceKey a = key(1, "/Music/a.mp3");
        ReferenceKey b = key(2, "/Music/b.mp3");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(a, b), map(song(2, "a"), song(1, "b")), Set.of());
        assertEquals(a.withId(2), plan.relocations.get(a));
        assertEquals(b.withId(1), plan.relocations.get(b));
    }

    @Test public void absentPathLeavesTheReferenceUnchanged() {
        ReferenceKey key = key(1, "/Music/gone.mp3");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(key), map(song(9, "other")), Set.of());
        assertTrue(plan.relocations.isEmpty());
        assertTrue(plan.deletedKeys.isEmpty());
    }

    @Test public void referenceWithNoPathIsLeftAlone() {
        ReferenceKey legacy = ReferenceKey.parse("1");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(legacy), map(song(9, "a")), Set.of());
        assertTrue(plan.relocations.isEmpty());
    }

    @Test public void confirmedDeletedPathRemovesTheReference() {
        ReferenceKey key = key(1, "/Music/a.mp3");
        ReferencePlan plan = LibraryReferenceReconciler.derive(
                List.of(key), map(), Set.of("/Music/a.mp3"));
        assertEquals(Set.of(key), plan.deletedKeys);
        assertTrue(plan.relocations.isEmpty());
    }

    @Test public void resolvePathFindsTheTrackAtThatLocation() {
        Song song = song(9, "a");
        assertEquals(9, LibraryReferenceReconciler
                .resolvePath("/Music/a.mp3", map(song)).id);
        assertNull(LibraryReferenceReconciler.resolvePath("/Music/missing.mp3", map(song)));
    }

    private static ReferenceKey key(long id, String path) {
        return new ReferenceKey(id, path);
    }

    private static Map<Long, Song> map(Song... songs) {
        Map<Long, Song> result = new java.util.LinkedHashMap<>();
        for (Song song : songs) result.put(song.id, song);
        return result;
    }

    private static Song song(long id, String name) {
        Song song = new Song(id, name, 1, 2024, 1000, "/Music/" + name + ".mp3", 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/" + id;
        return song;
    }
}
