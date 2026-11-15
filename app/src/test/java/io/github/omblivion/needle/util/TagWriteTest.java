package io.github.omblivion.needle.util;

import static org.junit.Assert.assertArrayEquals;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = App.class)
public class TagWriteTest {
    @Test
    public void shorterEditedAudioDoesNotLeaveOldTrailingBytes() throws Exception {
        File original = temporaryFile(new byte[] {1, 2, 3, 4, 5, 6});
        File edited = temporaryFile(new byte[] {9, 8});
        registerProvider(original);
        write(edited, false);
        assertArrayEquals(new byte[] {9, 8}, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void failedTagCommitDoesNotTruncateOriginalAudio() throws Exception {
        File original = temporaryFile(new byte[] {1, 2, 3, 4});
        File edited = temporaryFile(new byte[] {9, 8});
        registerProvider(original);
        write(edited, true);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void copiesAudioAcrossMultipleBufferBoundaries() throws Exception {
        byte[] expected = new byte[1024 * 1024 + 17];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i % 251);
        File original = temporaryFile(new byte[] {1, 2, 3});
        File edited = temporaryFile(expected);
        registerProvider(original);
        write(edited, false);
        assertArrayEquals(expected, Files.readAllBytes(original.toPath()));
    }

    @Test
    public void missingReplacementSourceDoesNotTruncateOriginalAudio() throws Exception {
        File original = temporaryFile(new byte[] {1, 2, 3, 4});
        File missing = new File(original.getParentFile(), "missing-replacement-audio.bin");
        registerProvider(original);
        write(missing, false);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(original.toPath()));
    }

    private void write(File edited, boolean fail) {
        // The tag library boundary is a fake; all file copying and provider IO is real.
        AudioFile audio = new AudioFile() {
            @Override public File getFile() { return edited; }
            @Override public void commit() throws CannotWriteException {
                if (fail) throw new CannotWriteException("Simulated tag encoding failure");
            }
        };
        Song song = new Song(42, "Track", 1, 2024, 1000, "/Music/a.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        SAFUtil.write(RuntimeEnvironment.getApplication(), AutoCloseAudioFile.create(audio), song);
    }

    private File temporaryFile(byte[] content) throws IOException {
        File file = File.createTempFile("audio", ".bin", RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(file.toPath(), content);
        return file;
    }

    private void registerProvider(File file) {
        FileProvider provider = new FileProvider(file);
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "media";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal("media", provider);
    }

    public static class FileProvider extends ContentProvider {
        private final File file;
        FileProvider(File file) { this.file = file; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
