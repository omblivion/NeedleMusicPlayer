package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class MetadataRefreshTest {
    @Test public void sourceChangingDuringReadDiscardsTagsAndRequestsRetry() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "a.mp3");
        Files.write(file.toPath(), new byte[]{1, 2});
        Song source = song(file);
        MetadataReadResult result = MetadataReadResult.readStable(source, detached -> {
            detached.title = "Partial tags";
            try { Files.write(file.toPath(), new byte[]{1, 2, 3}); }
            catch (Exception e) { throw new AssertionError(e); }
            return MetadataReadResult.Status.SUCCESS;
        });
        assertEquals(MetadataReadResult.Status.UNSTABLE, result.status);
        assertEquals("Provider title", result.song.title);
        assertTrue(result.song.metadataRetryNeeded);
        assertEquals("Provider title", source.title);
    }

    @Test public void unsupportedRetainsProviderFallbackAndDoesNotRetryUnchangedVersion() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "a.opus");
        Files.write(file.toPath(), new byte[]{1, 2});
        MetadataReadResult result = MetadataReadResult.readStable(song(file),
                detached -> MetadataReadResult.Status.UNSUPPORTED);
        assertEquals("Provider title", result.song.title);
        assertFalse(result.song.metadataRetryNeeded);
    }

    @Test public void transientFailureRetriesWithoutPublishingPartialTags() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "a.mp3");
        Files.write(file.toPath(), new byte[]{1, 2});
        MetadataReadResult result = MetadataReadResult.readStable(song(file), detached -> {
            detached.title = "Partial";
            return MetadataReadResult.Status.RETRYABLE_FAILURE;
        });
        assertEquals("Provider title", result.song.title);
        assertTrue(result.song.metadataRetryNeeded);
        MetadataReadResult recovered = MetadataReadResult.readStable(result.song, detached -> {
            detached.title = "Recovered";
            return MetadataReadResult.Status.SUCCESS;
        });
        assertEquals("Recovered", recovered.song.title);
        assertFalse(recovered.song.metadataRetryNeeded);
    }

    private static Song song(File file) {
        return new Song(1, "Provider title", 1, 2024, 1000, file.getPath(), 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
    }
}
