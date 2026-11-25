package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryFileDiscoveryTest {
    @Test public void targetedFileScanDoesNotEnumerateSiblingTracks() throws Exception {
        File root = RuntimeEnvironment.getApplication().getCacheDir();
        File target = file(root, "nested/one.mp3");
        File sibling = file(root, "nested/two.mp3");
        var result = new LibraryFileDiscovery(List.of()).discover(List.of(target));
        assertTrue(result.complete);
        assertEquals(java.util.Set.of(target.getCanonicalPath()), result.files.keySet());
        assertFalse(result.covers(sibling.getPath()));
    }

    @Test public void unchangedProviderObservationDoesNotRequireColdStartReindex() throws Exception {
        File root = RuntimeEnvironment.getApplication().getCacheDir();
        File file = file(root, "a.mp3");
        java.nio.file.Files.write(file.toPath(), new byte[]{1, 2});
        io.github.omblivion.needle.model.Song indexed =
                new io.github.omblivion.needle.model.Song(1, "A", 1, 2024, 1000,
                        file.getPath(), 1, file.lastModified() / 1000, 1, "Album", List.of("Artist"));
        indexed.sourceSize = 2;
        LibraryFileDiscovery.Observation observation = new LibraryFileDiscovery.Observation(file);
        assertTrue(observation.matches(indexed));
        indexed.sourceSize = 3;
        assertFalse(observation.matches(indexed));
    }
    @Test public void findsNestedDistinctBasenamesAndSkipsExcludedTrees() throws Exception {
        File root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "Music");
        File sibling = new File(root.getParentFile(), "MusicBackup");
        File a = file(root, "Été/a.mp3");
        File b = file(root, "other/a.mp3");
        file(root, ".sync/pending.mp3");
        file(root, "blocked/b.mp3");
        file(root, "nomedia/.nomedia");
        file(root, "nomedia/c.mp3");
        file(root, "temporary.mp3.tmp");
        file(sibling, "d.mp3");
        LibraryFileDiscovery.DiscoveryResult result = new LibraryFileDiscovery(
                List.of(new File(root, "blocked").getCanonicalPath()))
                .discover(List.of(root, sibling));
        assertTrue(result.complete);
        assertEquals(3, result.files.size());
        assertTrue(result.files.containsKey(a.getCanonicalPath()));
        assertTrue(result.files.containsKey(b.getCanonicalPath()));
    }

    @Test public void unreadableDirectoryIsNotAnEmptySuccessfulScan() {
        File inaccessible = new File("/unavailable-root") {
            @Override public boolean isDirectory() { return true; }
            @Override public File[] listFiles() { return null; }
        };
        LibraryFileDiscovery.DiscoveryResult result =
                new LibraryFileDiscovery(List.of()).discover(List.of(inaccessible));
        assertFalse(result.complete);
        assertTrue(result.files.isEmpty());
    }

    @Test public void configuredRootAliasIsCanonicalizedInsteadOfSilentlySkipped() throws Exception {
        File actual = new File(RuntimeEnvironment.getApplication().getCacheDir(), "Music");
        file(actual, "nested/a.mp3");
        File alias = new File(actual.getParentFile(), "alias") {
            @Override public String getCanonicalPath() throws java.io.IOException {
                return actual.getCanonicalPath();
            }
        };
        LibraryFileDiscovery.DiscoveryResult result =
                new LibraryFileDiscovery(List.of()).discover(List.of(alias));
        assertTrue(result.complete);
        assertEquals(1, result.files.size());
    }

    private static File file(File root, String name) throws Exception {
        File file = new File(root, name);
        file.getParentFile().mkdirs();
        file.createNewFile();
        return file;
    }
}
