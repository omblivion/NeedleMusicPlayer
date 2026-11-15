package io.github.omblivion.needle.discog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.BlacklistStore;
import io.github.omblivion.needle.util.PreferenceUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class MediaStoreFailureTest {
    private Discography discography;
    private final ScanProvider provider = new ScanProvider();

    @Before
    public void setUp() {
        ReflectionHelpers.setStaticField(BlacklistStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(PreferenceUtil.class, "sInstance", null);
        discography = App.getDiscography();
        Song song = new Song(42, "Existing", 1, 2024, 1000, "/Music/existing.mp3",
                0, 0, 1, "Album", new ArrayList<>(List.of("Artist")));
        ReflectionHelpers.callInstanceMethod(discography, "addSong",
                ReflectionHelpers.ClassParameter.from(Song.class, song),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        ShadowContentResolver.registerProviderInternal("media", provider);
    }

    @Test
    public void nullCursorDoesNotEraseExistingLibrary() {
        assertThrows(RuntimeException.class, () -> discography.syncWithMediaStore(progress -> {}));
        assertEquals(1, discography.getAllSongs(null).size());
    }

    @Test
    public void permissionFailureDoesNotEraseExistingLibrary() {
        provider.fail = true;
        assertThrows(SecurityException.class, () -> discography.syncWithMediaStore(progress -> {}));
        assertEquals(1, discography.getAllSongs(null).size());
    }

    @Test
    public void successfulEmptyQueryRemovesMissingSongs() {
        provider.empty = true;
        discography.syncWithMediaStore(progress -> {});
        assertEquals(0, discography.getAllSongs(null).size());
    }

    @Test
    public void folderBlacklistDoesNotExcludeSiblingWithSamePrefix() throws Exception {
        java.io.File root = RuntimeEnvironment.getApplication().getCacheDir();
        java.io.File music = new java.io.File(root, "Music");
        java.io.File sibling = new java.io.File(root, "MusicBackup");
        music.mkdirs();
        sibling.mkdirs();
        java.io.File songFile = new java.io.File(sibling, "track.mp3");
        songFile.createNewFile();
        provider.songPath = songFile.getPath();
        BlacklistStore.getInstance(RuntimeEnvironment.getApplication()).addPath(music);
        discography.syncWithMediaStore(progress -> {});
        assertEquals(1, discography.getAllSongs(null).size());
    }

    public static class ScanProvider extends ContentProvider {
        boolean fail;
        boolean empty;
        String songPath;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            if (fail) throw new SecurityException("Audio permission revoked");
            if (songPath != null) {
                MatrixCursor cursor = new MatrixCursor(projection);
                cursor.addRow(new Object[] {99L, "Sibling track", 1, 2024, 1000L, songPath,
                        0L, 0L, 1L, "Album", "Artist"});
                return cursor;
            }
            return empty ? new MatrixCursor(projection) : null;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
