package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import android.database.sqlite.SQLiteDatabase;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class DiscographyMigrationTest {
    @Test
    public void versionSevenUpgradePreservesCatalogAndDefaultsUnknownSource() {
        try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(
                RuntimeEnvironment.getApplication().getDatabasePath("discography.db"), null)) {
            old.execSQL("CREATE TABLE songs (id LONG NOT NULL, album_id LONG, "
                    + "album_artist_name TEXT, album_name TEXT, artist_id LONG, artist_name TEXT, "
                    + "data_path TEXT, date_added LONG, date_modified LONG, disc_number LONG, "
                    + "genre TEXT, replaygain_album REAL, replaygain_track REAL, "
                    + "replaygainpeak_album REAL, replaygainpeak_track REAL, track_duration LONG, "
                    + "track_number LONG, track_title TEXT, year LONG)");
            old.execSQL("INSERT INTO songs VALUES (42, 7, 'Album artist', 'Album', NULL, 'Artist', "
                    + "'/Music/a.mp3', 10, 20, 2, 'Rock', 1.5, 2.5, 0.9, 0.8, 123000, 3, 'Old title', 2024)");
            old.setVersion(7);
        }
        try (DB database = new DB()) {
            Song song = database.fetchAllSongs().iterator().next();
            assertEquals(8, database.getReadableDatabase().getVersion());
            assertEquals(42, song.id);
            assertEquals("Old title", song.title);
            assertEquals("/Music/a.mp3", song.data);
            assertEquals(List.of("Album artist"), song.albumArtistNames);
            assertEquals(123000, song.duration);
            assertEquals(2.5f, song.replayGainTrack, 0.001f);
            assertEquals("", song.contentUri);
            assertEquals(-1, song.sourceSize);
            assertEquals(-1, song.sourceGeneration);
            assertEquals("", song.providerVersion);
            assertTrue(song.metadataRetryNeeded);
        }
    }

    @Test
    public void sourceBookkeepingSurvivesDatabaseReopen() {
        Song song = new Song(42, "Track", 3, 2024, 123000, "/Music/a.mp3", 10, 20,
                7, "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/42";
        song.sourceSize = 3456;
        song.sourceGeneration = 9;
        song.providerVersion = "provider-v1";
        song.metadataRetryNeeded = false;
        try (DB database = new DB()) {
            database.addSong(song);
        }
        try (DB database = new DB()) {
            Song restored = database.fetchAllSongs().iterator().next();
            assertEquals(song.contentUri, restored.contentUri);
            assertEquals(3456, restored.sourceSize);
            assertEquals(9, restored.sourceGeneration);
            assertEquals("provider-v1", restored.providerVersion);
            assertFalse(restored.metadataRetryNeeded);
        }
    }
}
