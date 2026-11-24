package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import android.database.sqlite.SQLiteException;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryPublicationTest {
    @Test public void catalogListenerReceivesCommittedRevisionWithoutAttachedActivity() {
        Discography discography = App.getDiscography();
        List<Integer> counts = new ArrayList<>();
        discography.addChangedListener(() -> counts.add(discography.getAllSongs(null).size()));
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1), song(2)), 0));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(250));
        assertEquals(List.of(2), counts);
    }
    @Test
    public void stagedCatalogIsInvisibleUntilPublicationAndDetachesInputs() {
        Discography discography = App.getDiscography();
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1)), 0));
        Song incoming = song(2);
        LibrarySnapshot next = new LibrarySnapshot(List.of(incoming, song(3)), 1);
        incoming.title = "Mutated outside snapshot";
        assertEquals(1, discography.getAllSongs(null).size());
        assertEquals(1, discography.getAllSongs(null).get(0).id);
        discography.publishSnapshot(next);
        assertEquals(2, discography.getAllSongs(null).size());
        assertEquals("Track 2", discography.getSong(2).title);
        assertEquals(Song.EMPTY_SONG, discography.getSong(1));
    }

    @Test
    public void failedReplacementKeepsOldDatabaseAndCache() {
        Discography discography = App.getDiscography();
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1)), 0));
        DB db = ReflectionHelpers.getField(discography, "database");
        db.getWritableDatabase().execSQL("CREATE TRIGGER reject_third BEFORE INSERT ON songs "
                + "WHEN NEW.id=3 BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END");
        assertThrows(SQLiteException.class,
                () -> discography.publishSnapshot(new LibrarySnapshot(List.of(song(2), song(3)), 1)));
        assertEquals(1, discography.getAllSongs(null).get(0).id);
        assertEquals(1, db.fetchAllSongs().iterator().next().id);
        assertEquals(1, db.fetchAllSongs().size());
    }

    @Test
    public void obsoleteRevisionCannotOverwriteNewerCatalog() {
        Discography discography = App.getDiscography();
        discography.publishSnapshot(new LibrarySnapshot(List.of(song(1)), 0));
        assertThrows(IllegalStateException.class,
                () -> discography.publishSnapshot(new LibrarySnapshot(List.of(song(2)), 0)));
        assertEquals(1, discography.getAllSongs(null).get(0).id);
    }

    private static Song song(long id) {
        return new Song(id, "Track " + id, 1, 2024, 1000, "/Music/" + id + ".mp3",
                1, 2, 7, "Album", new ArrayList<>(List.of("Artist")));
    }
}
