package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class MediaStoreSourceTest {
    @Test public void blankTitleDoesNotExcludeMusicAndFallsBackToFilename() {
        SourceProvider provider = new SourceProvider();
        provider.blankTitle = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        Song song = MediaStoreBridge.getAllSongs(RuntimeEnvironment.getApplication()).get(0);
        assertEquals("a.mp3", song.title);
    }
    @Test
    public void providerObservationKeepsVolumeSizeAndGeneration() {
        ShadowContentResolver.registerProviderInternal("media", new SourceProvider());
        Song song = MediaStoreBridge.getAllSongs(RuntimeEnvironment.getApplication()).get(0);
        assertEquals("content://media/external_primary/audio/media/42", song.contentUri);
        assertEquals(12000, song.sourceSize);
        assertEquals(77, song.sourceGeneration);
        assertEquals("/Music/album/a.mp3", song.data);
        assertEquals(2, song.discNumber);
        assertEquals(3, song.trackNumber);
    }

    public static class SourceProvider extends ContentProvider {
        boolean blankTitle;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (blankTitle && selection != null && selection.contains("title")) return cursor;
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : projection) {
                switch (column) {
                    case "_id": row.add(42L); break;
                    case "title": row.add(blankTitle ? "" : "Track"); break;
                    case "track": row.add(2003); break;
                    case "year": row.add(2024); break;
                    case "duration": row.add(1000L); break;
                    case "_data": row.add("/Music/album/a.mp3"); break;
                    case "date_added": row.add(1L); break;
                    case "date_modified": row.add(2L); break;
                    case "album_id": row.add(7L); break;
                    case "album": row.add("Album"); break;
                    case "artist": row.add("Artist"); break;
                    case "_size": row.add(12000L); break;
                    case "generation_modified": row.add(77L); break;
                    case "generation_added": row.add(1L); break;
                    case "volume_name": row.add(MediaStore.VOLUME_EXTERNAL_PRIMARY); break;
                    default: throw new AssertionError("Unexpected column " + column);
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
