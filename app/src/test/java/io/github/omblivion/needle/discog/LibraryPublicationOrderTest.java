package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.PreferencesBackedSongList;
import io.github.omblivion.needle.provider.StaticPlaylist;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryPublicationOrderTest {
    @Before @After public void resetPreferences() {
        ReflectionHelpers.setStaticField(PreferencesBackedSongList.class, "preferences", null);
        ReflectionHelpers.setStaticField(io.github.omblivion.needle.provider.HistoryStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(io.github.omblivion.needle.provider.SongPlayCountStore.class, "sInstance", null);
        ReferenceReconciliationJournal.applyGate = ReferenceReconciliationJournal.ApplyGate.NONE;
    }

    @Test public void swapPreservesEachTrackReferencesAfterPublication() {
        Discography discography = App.getDiscography();
        Song first = song(1, "a");
        Song second = song(2, "b");
        discography.publishSnapshot(new LibrarySnapshot(List.of(first, second), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(first, second, first));

        discography.publishSnapshot(new LibrarySnapshot(List.of(song(2, "a"), song(1, "b")), 1));

        assertEquals(List.of(2L, 1L, 2L), ids(new StaticPlaylist("Keep")));
    }

    @Test public void recycledIdDoesNotInheritReferences() {
        Discography discography = App.getDiscography();
        Song original = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(original), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(original));

        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1, "unrelated")), 1));

        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
    }

    @Test public void pathIsTheOnlyTruthDuringPublication() {
        Discography discography = App.getDiscography();
        Song original = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(original), 0));

        discography.publishSnapshot(new LibrarySnapshot(List.of(song(9, "a")), 1));

        assertEquals(9, discography.resolveReference(original).id);
    }

    private static List<Long> ids(StaticPlaylist playlist) {
        List<Long> ids = new ArrayList<>();
        for (Song song : playlist.asSongs()) ids.add(song.id);
        return ids;
    }

    private static Song song(long id, String name) {
        Song song = new Song(id, name, 1, 2024, 1000, "/Music/" + name + ".mp3", 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/" + id;
        return song;
    }
}
