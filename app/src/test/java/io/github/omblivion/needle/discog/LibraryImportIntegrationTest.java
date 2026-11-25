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

    @Test public void incrementalImportKeepsReferencesWhenStorageStopsReportingAFile() throws Exception {
        exercise(false);
    }
    @Test public void fullRebuildKeepsReferencesWhenStorageStopsReportingAFile() throws Exception {
        exercise(true);
    }

    @Test public void initialImportPublishesIndexedSongsBeforeReadingIndividualTags() throws Exception {
        File root = Files.createTempDirectory("needle-initial").toFile();
        File nested = new File(root, "album/track.mp3");
        assertTrue(nested.getParentFile().mkdirs());
        Files.write(nested.toPath(), new byte[]{1});
        var provider = new ImportProvider();
        provider.file = nested;
        provider.id = 1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        var discovery = new LibraryFileDiscovery(List.of()).discover(List.of(root));
        // An ordinary incomplete scan is the same pre-index situation: indexed
        // songs are usable immediately, without per-track tag or URI queries.
        discography.syncWithMediaStore(p -> {}, false, discovery, false);
        assertEquals("Provider title", discography.getSong(1).title);
        assertEquals(0, provider.trackQueries);
    }

    @Test public void automaticRefreshDoesNotRewriteUnchangedRows() throws Exception {
        File root = Files.createTempDirectory("needle-library").toFile();
        File one = new File(root, "one.mp3");
        File two = new File(root, "two.mp3");
        Files.write(one.toPath(), new byte[]{1});
        Files.write(two.toPath(), new byte[]{2});
        ImportProvider provider = new ImportProvider();
        provider.file = one;
        provider.id = 1;
        provider.otherFile = two;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        // The tiny fixtures cannot supply tags; let the bounded tag retries settle.
        for (int i = 0; i < 4; i++) scan(discography, root, false);
        long before = discography.getRevision();
        try (DB helper = new DB()) {
            var db = helper.getWritableDatabase();
            db.execSQL("CREATE TABLE audit (id LONG)");
            for (String operation : List.of("INSERT", "DELETE", "UPDATE")) {
                db.execSQL("CREATE TRIGGER audit_" + operation + " AFTER " + operation + " ON songs"
                        + " BEGIN INSERT INTO audit VALUES ("
                        + ("DELETE".equals(operation) ? "OLD" : "NEW") + ".id); END");
            }
            scan(discography, root, false);
            assertEquals(before, discography.getRevision());
            try (Cursor c = db.rawQuery("SELECT id FROM audit", null)) {
                assertEquals(0, c.getCount());
            }
            Files.write(one.toPath(), new byte[]{1, 3});
            scan(discography, root, false);
            try (Cursor c = db.rawQuery("SELECT DISTINCT id FROM audit", null)) {
                assertTrue(c.moveToFirst());
                assertEquals(1, c.getLong(0));
                assertFalse(c.moveToNext());
            }
            assertEquals(2, discography.getAllSongs(null).size());
        }
    }

    @Test public void scanSkipsPathlessRowsAndDoesNotImportOutsideTheRequestedRoot() throws Exception {
        File root = Files.createTempDirectory("needle-selected").toFile();
        File nested = new File(root, "nested/inside.mp3");
        assertTrue(nested.getParentFile().mkdirs());
        Files.write(nested.toPath(), new byte[]{1});
        File outside = Files.createTempFile("needle-outside", ".mp3").toFile();
        ImportProvider provider = new ImportProvider();
        provider.file = nested;
        provider.id = 1;
        provider.otherFile = outside;
        provider.includePathless = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        scan(discography, root, false);
        assertEquals(List.of(1L), discography.getAllSongs(null).stream().map(song -> song.id).toList());
    }

    @Test public void incompleteRebuildDoesNotPublishPartialResults() throws Exception {
        File root = Files.createTempDirectory("needle-rebuild").toFile();
        File file = new File(root, "one.mp3");
        Files.write(file.toPath(), new byte[]{1});
        ImportProvider provider = new ImportProvider();
        provider.file = file;
        provider.id = 1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        scan(discography, root, false);
        long previous = discography.getRevision();
        provider.id = 2;
        var discovery = new LibraryFileDiscovery(List.of()).discover(List.of(root));
        discography.syncWithMediaStore(p -> {}, true, discovery, false);
        assertEquals(previous, discography.getRevision());
        assertEquals(1, discography.getSong(1).id);
    }

    @Test public void recycledIdDoesNotMergeOrBlockPathAnchoredStatistics() {
        var context = RuntimeEnvironment.getApplication();
        Discography discography = App.getDiscography();
        Song first = LibraryReferenceReconciliationTest.song(1, "first");
        Song other = LibraryReferenceReconciliationTest.song(2, "other");
        discography.publishSnapshot(new LibrarySnapshot(List.of(first, other), 0));
        var stats = SongPlayCountStore.getInstance(context);
        long now = System.currentTimeMillis();
        stats.recordPlay(ReferenceKey.of(first), now, "first", 0);
        stats.recordPlay(ReferenceKey.of(other), now, "other-1", 0);

        // The other file drops out of the catalog while the first gets its old ID.
        Song reindexed = new Song(first, 2);
        discography.publishSnapshot(new LibrarySnapshot(List.of(reindexed), 1));
        stats.recordPlay(ReferenceKey.of(reindexed), now, "first-2", 0);

        try (Cursor c = stats.getReadableDatabase().rawQuery(
                "SELECT path,total_count FROM play_count_stats WHERE song_id=2 ORDER BY path", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(ReferenceKey.of(first).path(), c.getString(0));
            assertEquals(2, c.getLong(1));
            assertTrue(c.moveToNext());
            assertEquals(ReferenceKey.of(other).path(), c.getString(0));
            assertEquals(1, c.getLong(1));
            assertFalse(c.moveToNext());
        }
    }

    @Test public void targetedImportDoesNotEvictAnOutOfScopeTrackWithTheSameId() throws Exception {
        File root = Files.createTempDirectory("needle-collision").toFile();
        File kept = new File(root, "kept.mp3");
        File target = new File(root, "target.mp3");
        Files.write(kept.toPath(), new byte[]{1});
        Files.write(target.toPath(), new byte[]{2});
        io.github.omblivion.needle.util.PreferenceUtil.getInstance().setStartDirectory(root);
        Discography discography = App.getDiscography();
        Song old = new Song(1, "Keep", 1, 0, 1, kept.getPath(), 0, 0, 7, "Album", List.of("Artist"));
        discography.publishSnapshot(new LibrarySnapshot(List.of(old), 0));
        ImportProvider provider = new ImportProvider();
        provider.file = target;
        provider.id = 1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        var discovery = new LibraryFileDiscovery(List.of()).discover(List.of(target));
        var result = discography.syncWithMediaStore(p -> {}, true, discovery, true, true);
        assertTrue(result.deferred);
        assertEquals(kept.getPath(), discography.getSong(1).data);
        assertEquals(1, discography.getRevision());
    }

    @Test public void scanDoesNotPublishAfterItsRootSelectionBecomesObsolete() throws Exception {
        File root = Files.createTempDirectory("needle-obsolete").toFile();
        File track = new File(root, "one.mp3");
        Files.write(track.toPath(), new byte[]{1});
        ImportProvider provider = new ImportProvider();
        provider.file = track;
        provider.id = 1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Discography discography = App.getDiscography();
        var discovery = new LibraryFileDiscovery(List.of()).discover(List.of(root));
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var result = discography.syncWithMediaStore(progress -> current.set(false), true, discovery, true,
                false, current::get);
        assertTrue(result.deferred);
        assertEquals(0, discography.getRevision());
        assertTrue(discography.getAllSongs(null).isEmpty());
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
            assertEquals(1, c.getCount());
        }
        Discography restarted = new Discography();
        restarted.ensureLoaded();
        assertTrue(restarted.getAllSongs(null).isEmpty());

        // Repeated provider/filesystem absence changes the catalog, not the
        // data attached to this path. It may become available again.
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        provider.present = true;
        provider.id = 11;
        scan(discography, root, rebuild);
        assertEquals(List.of(11L, 11L),
                new StaticPlaylist("Keep").asSongs().stream().map(song -> song.id).toList());
    }

    private static void scan(Discography discography, File root, boolean rebuild) {
        LibraryFileDiscovery.DiscoveryResult discovery =
                new LibraryFileDiscovery(List.of()).discover(List.of(root));
        discography.syncWithMediaStore(p -> {}, rebuild, discovery, true);
    }

    public static class ImportProvider extends ContentProvider {
        File file;
        File otherFile;
        long id;
        long size = -1;
        boolean present = true;
        boolean includePathless;
        int trackQueries;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (!present) return cursor;
            if (args != null) {
                trackQueries++;
                if (file.getPath().equals(args[0])) addRow(cursor, projection, file, id, size);
                else if (otherFile != null && otherFile.getPath().equals(args[0])) {
                    addRow(cursor, projection, otherFile, 100, -1);
                }
                return cursor;
            }
            String last = uri.getLastPathSegment();
            if (last != null && last.matches("\\d+")) {
                trackQueries++;
                if (last.equals(Long.toString(id))) addRow(cursor, projection, file, id, size);
                else if (last.equals("100") && otherFile != null) addRow(cursor, projection, otherFile, 100, -1);
                return cursor;
            }
            if (includePathless) addRow(cursor, projection, null, 101, 0);
            addRow(cursor, projection, file, id, size);
            if (otherFile != null) addRow(cursor, projection, otherFile, 100, -1);
            return cursor;
        }
        private static void addRow(MatrixCursor cursor, String[] projection, File file, long id, long size) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : projection) {
                switch (column) {
                    case "_id": row.add(id); break;
                    case "title": row.add("Provider title"); break;
                    case "_data": row.add(file == null ? null : file.getPath()); break;
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
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
