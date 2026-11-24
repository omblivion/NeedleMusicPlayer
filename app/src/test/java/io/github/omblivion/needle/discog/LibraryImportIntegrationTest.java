package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryImportIntegrationTest {
    @Before @After public void resetStores() {
        ReflectionHelpers.setStaticField(PreferencesBackedSongList.class, "preferences", null);
        ReflectionHelpers.setStaticField(HistoryStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(SongPlayCountStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(MusicPlaybackQueueStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", null);
    }

    @Test public void incrementalImportUpdateTemporaryLossAndConfirmedRemovalPreserveReferences() throws Exception {
        exercise(false);
    }
    @Test public void fullReconstructionUpdateTemporaryLossAndConfirmedRemovalPreserveReferences() throws Exception {
        exercise(true);
    }

    /** A full rescan reuses the same provider ID because the file never changed identity. */
    private void exercise(boolean rebuild) throws Exception {
        File root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "Music");
        File file = new File(root, "nested/a.mp3");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[]{1, 2});
        ImportProvider provider = new ImportProvider();
        provider.file = file;
        provider.id = 1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        scan(discography, root, rebuild);
        Song original = discography.getSong(1);
        assertEquals("Provider title", original.title);
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(original, original));
        SongPlayCountStore stats = SongPlayCountStore.getInstance(RuntimeEnvironment.getApplication());
        stats.recordPlay(new io.github.omblivion.needle.provider.ReferenceKey(1, file.getPath()),
                System.currentTimeMillis(), "heard", 0);
        provider.id = 9;
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        // Same path, larger size and a new provider ID is a verified update.
        provider.size = 3;
        scan(discography, root, rebuild);
        assertEquals(9, new StaticPlaylist("Keep").asSongs().get(0).id);
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        try (Cursor c = stats.getReadableDatabase().rawQuery(
                "SELECT total_count FROM play_count_stats WHERE song_id=9", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getLong(0));
        }
        provider.present = false;
        File offline = new File(root.getParentFile(), "offline");
        assertTrue(root.renameTo(offline));
        scan(discography, root, rebuild);
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        assertTrue(offline.renameTo(root));
        provider.present = true;
        scan(discography, root, rebuild);
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        assertTrue(file.delete());
        provider.present = false;
        scan(discography, root, rebuild);
        assertEquals(2, new StaticPlaylist("Keep").asSongs().size());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        scan(discography, root, rebuild);
        assertTrue(new StaticPlaylist("Keep").asSongs().isEmpty());
        assertTrue(discography.getAllSongs(null).isEmpty());
        try (Cursor c = stats.getReadableDatabase().rawQuery("SELECT * FROM play_count_stats", null)) {
            assertEquals(0, c.getCount());
        }
        Discography restarted = new Discography();
        restarted.ensureLoaded();
        assertTrue(restarted.getAllSongs(null).isEmpty());
    }

    private static void scan(Discography discography, File root, boolean rebuild) {
        LibraryFileDiscovery.DiscoveryResult discovery =
                new LibraryFileDiscovery(List.of()).discover(List.of(root));
        discography.syncWithMediaStore(p -> {}, rebuild, discovery, true);
    }

    public static class ImportProvider extends ContentProvider {
        File file;
        long id;
        long size = -1;
        boolean present = true;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (!present) return cursor;
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : projection) {
                switch (column) {
                    case "_id": row.add(id); break;
                    case "title": row.add("Provider title"); break;
                    case "_data": row.add(file.getPath()); break;
                    case "album": row.add("Album"); break;
                    case "artist": row.add("Artist"); break;
                    case "volume_name": row.add("external_primary"); break;
                    case "_size": row.add(size < 0 ? file.length() : size); break;
                    case "generation_modified": row.add(1L); break;
                    case "generation_added": row.add(1L); break;
                    case "album_id": row.add(7L); break;
                    case "duration": row.add(1000L); break;
                    default: row.add(1L);
                }
            }
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
