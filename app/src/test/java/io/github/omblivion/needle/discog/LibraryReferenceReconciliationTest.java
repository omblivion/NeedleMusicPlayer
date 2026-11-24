package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import android.database.Cursor;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.*;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryReferenceReconciliationTest {
    @Before @org.junit.After public void resetStores() {
        org.robolectric.util.ReflectionHelpers.setStaticField(PreferencesBackedSongList.class, "preferences", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(HistoryStore.class, "sInstance", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(SongPlayCountStore.class, "sInstance", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(MusicPlaybackQueueStore.class, "sInstance", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", null);
    }
    @Test public void readingPlaylistDuringEmptyCatalogDoesNotEraseMembership() {
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(song(1, "a"), song(1, "a")));
        assertTrue(playlist.asSongs().isEmpty());
        App.getDiscography().publishSnapshot(new LibrarySnapshot(List.of(song(1, "a")), 0));
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
    }

    @Test public void idChangePreservesDuplicatesHistoryAndEveryStatsField() {
        Discography discography = App.getDiscography();
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1, "a")), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(song(1, "a"), song(1, "a")));
        HistoryStore history = HistoryStore.getInstance(RuntimeEnvironment.getApplication());
        history.addSongId(1, "/Music/a.mp3");
        SongPlayCountStore stats = SongPlayCountStore.getInstance(RuntimeEnvironment.getApplication());
        stats.recordPlay(new io.github.omblivion.needle.provider.ReferenceKey(1, "/Music/a.mp3"),
                System.currentTimeMillis(), "occurrence", 0);
        stats.getWritableDatabase().execSQL("UPDATE play_count_stats SET total_count=17, reset_version=4");
        stats.getWritableDatabase().execSQL("INSERT INTO legacy_week VALUES (1, 2000, 9)");
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(9, "a")), 1));
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        assertEquals(9, new StaticPlaylist("Keep").asSongs().get(0).id);
        assertTrue(history.contains(9));
        assertFalse(history.contains(1));
        try (Cursor cursor = stats.getReadableDatabase().rawQuery(
                "SELECT total_count,reset_version FROM play_count_stats WHERE song_id=9", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(17, cursor.getLong(0));
            assertEquals(4, cursor.getLong(1));
        }
        try (Cursor cursor = stats.getReadableDatabase().rawQuery(
                "SELECT occurred_at,occurrence FROM play_event WHERE song_id=9", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("occurrence", cursor.getString(1));
        }
        try (Cursor cursor = stats.getReadableDatabase().rawQuery(
                "SELECT legacy_count FROM legacy_week WHERE song_id=9", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(9, cursor.getInt(0));
        }
    }

    @Test public void idSwapDoesNotMixTwoSongsAndDeletionRemovesOnlyConfirmedReferences() {
        Discography discography = App.getDiscography();
        Song first = song(1, "a");
        Song second = song(2, "b");
        discography.publishSnapshot(new LibrarySnapshot(List.of(first, second), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(first, second, first));
        // Two files exchanged provider IDs. Identity follows the path, so the
        // references must stay attached to the same tracks, not the same IDs.
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(2, "a"), song(1, "b")), 1));
        assertEquals(List.of(2L, 1L, 2L), ids(new StaticPlaylist("Keep")));
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1, "b")), 2, Set.of(2L)));
        assertEquals(List.of(1L), ids(new StaticPlaylist("Keep")));
    }

    @Test public void reusedIdAtUnrelatedPathDoesNotInheritReferences() {
        Discography discography = App.getDiscography();
        Song original = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(original), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(original));
        // Android reuses ID 1 for an unrelated file; the old track's references
        // must not follow the numeric ID onto the new file.
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1, "unrelated")), 1));
        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
    }

    @Test public void confirmedUserDeletionCleansReferencesButTagInvalidationDoesNot() {
        Discography discography = App.getDiscography();
        Song a = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(a), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(a, a));
        discography.removeSongByPath(a.data);
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        discography.confirmDeletion(List.of(a));
        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
        assertEquals(Song.EMPTY_SONG, discography.getSong(1));
    }

    @Test public void verifiedRenamePreservesReferences() {
        Discography discography = App.getDiscography();
        Song before = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(before), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(before));
        // The file was renamed: with a path-anchored reference and no continuity
        // evidence, the reference is preserved but stays unresolved until the
        // original path returns.
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(9, "renamed")), 1));
        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
        discography.publishSnapshot(new LibrarySnapshot(
                List.of(song(9, "renamed"), song(3, "a")), discography.getRevision()));
        assertEquals("/Music/a.mp3", new StaticPlaylist("Keep").asSongs().get(0).data);
    }

    @Test public void renameWithoutContinuityParksTheReferenceInsteadOfGuessing() {
        Discography discography = App.getDiscography();
        Song before = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(before), 0));
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(before));
        // No creation generation on either side: never attach by similarity.
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(9, "renamed")), 1));
        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
    }

    @Test public void filteredSongIdentitySurvivesRestartAndLaterIdChange() {
        Discography discography = App.getDiscography();
        Song a = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(a), 0));
        StaticPlaylist keep = new StaticPlaylist("Keep");
        keep.addSongs(List.of(a));
        // The file moves to a different path (so it leaves the filtered scope) and
        // later comes back with a new provider ID.
        org.robolectric.util.ReflectionHelpers.setStaticField(App.class, "discography", new Discography());
        Discography restarted = App.getDiscography();
        restarted.ensureLoaded();
        restarted.publishSnapshot(new LibrarySnapshot(List.of(song(9, "a")), restarted.getRevision()));
        assertEquals(9, new StaticPlaylist("Keep").asSongs().get(0).id);
    }

    @Test public void occupiedRemapTargetIsRejectedBeforeJournalOrAnyStoreChanges() {
        Discography discography = App.getDiscography();
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1, "a"), song(2, "b")), 0));
        HistoryStore history = HistoryStore.getInstance(RuntimeEnvironment.getApplication());
        history.addSongId(2);
        // The file at /Music/a.mp3 comes back as ID 2, which still owns ID 2's
        // history. ID 2 keeps its own track, so the two references must not merge:
        // the second track is parked and only track b keeps its history.
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(2, "a")), 1));
        assertTrue(history.contains(2));
        assertFalse(history.contains(1));
        try (ReferenceReconciliationJournal journal = new ReferenceReconciliationJournal()) {
            assertNull(journal.read());
        }
    }

    @Test public void stalePlaylistObjectResolvesSourceAfterIdSwap() {
        Discography discography = App.getDiscography();
        Song a = song(1, "a");
        Song b = song(2, "b");
        discography.publishSnapshot(new LibrarySnapshot(List.of(a, b), 0));
        StaticPlaylist held = new StaticPlaylist("Keep");
        Song movedA = song(2, "a");
        Song movedB = song(1, "b");
        discography.publishSnapshot(new LibrarySnapshot(List.of(movedA, movedB), 1));
        held.addSongs(List.of(a));
        assertEquals(List.of(2L), ids(new StaticPlaylist("Keep")));
    }

    @Test public void deletedSourceDoesNotExcludeUnrelatedNewSourceWithReusedId() {
        Discography discography = App.getDiscography();
        Song a = song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(a), 0));
        discography.confirmDeletion(List.of(a));
        Song b = song(1, "b");
        discography.publishSnapshot(new LibrarySnapshot(List.of(b), discography.getRevision()));
        assertTrue(discography.isConfirmedDeleted(a));
        assertFalse(discography.isConfirmedDeleted(b));
    }

    private static List<Long> ids(StaticPlaylist playlist) {
        List<Long> ids = new ArrayList<>();
        for (Song song : playlist.asSongs()) ids.add(song.id);
        return ids;
    }
    static Song song(long id, String name) {
        Song song = new Song(id, name, 1, 2024, 1000, "/Music/" + name + ".mp3", 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/" + id;
        return song;
    }
}
