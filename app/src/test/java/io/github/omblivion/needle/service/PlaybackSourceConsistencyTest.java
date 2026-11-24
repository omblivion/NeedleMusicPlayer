package io.github.omblivion.needle.service;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import io.github.omblivion.needle.misc.queue.StaticPlayingQueue;
import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.service.playback.Playback;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class PlaybackSourceConsistencyTest {
    @Test
    public void openCurrentValidatesProviderBeforeHandingUriToPlayer() {
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/unrelated.mp3";
        ShadowContentResolver.registerProviderInternal("media", provider);
        MusicService service = Robolectric.buildService(MusicService.class).get();
        ProbePlayback player = new ProbePlayback();
        ReflectionHelpers.setField(service, "playback", player);
        setQueue(service, expected());
        assertFalse((boolean) ReflectionHelpers.callInstanceMethod(service, "openCurrent"));
        assertNull(player.openedUri);
    }

    @Test
    public void replacingQueueMetadataDoesNotRelabelAlreadyOpenedAudio() {
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/a.mp3";
        ShadowContentResolver.registerProviderInternal("media", provider);
        MusicService service = Robolectric.buildService(MusicService.class).get();
        ProbePlayback player = new ProbePlayback();
        ReflectionHelpers.setField(service, "playback", player);
        setQueue(service, expected());
        assertTrue((boolean) ReflectionHelpers.callInstanceMethod(service, "openCurrent"));
        Song replacement = expected();
        replacement.title = "Replacement";
        replacement.sourceGeneration = 100;
        setQueue(service, replacement);
        assertEquals("Expected", service.getCurrentSong().title);
        assertEquals(1, player.openCount);
    }

    @Test
    public void promotedSourceKeepsActualMetadataAfterQueueReorder() {
        MusicService service = Robolectric.buildService(MusicService.class).get();
        ProbePlayback player = new ProbePlayback();
        player.openedUri = "content://media/external_primary/audio/media/42";
        ReflectionHelpers.setField(service, "playback", player);
        Song promoted = expected();
        MusicService.PreparedSource prepared = new MusicService.PreparedSource(promoted, 1);
        setQueue(service, promoted);
        service.promotePreparedSource(prepared);
        assertEquals(0, service.getPosition());
        assertEquals("Expected", service.getCurrentSong().title);
    }

    private static void setQueue(MusicService service, Song song) {
        ArrayList<IndexedSong> queue = new ArrayList<>(List.of(new IndexedSong(song, 0, 0)));
        ReflectionHelpers.setField(service, "playingQueue",
                new StaticPlayingQueue(queue, new ArrayList<>(queue), 0, 0, 0));
    }

    private static class ProbePlayback implements Playback {
        String openedUri;
        int openCount;
        @Override public boolean setDataSource(String path) { openedUri = path; openCount++; return true; }
        @Override public void setNextDataSource(String path) {}
        @Override public void setCallbacks(PlaybackCallbacks callbacks) {}
        @Override public boolean isInitialized() { return openedUri != null; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void release() {}
        @Override public void pause() {}
        @Override public boolean isPlaying() { return openedUri != null; }
        @Override public int duration() { return 1000; }
        @Override public int position() { return 0; }
        @Override public void seek(int position) {}
        @Override public boolean setAudioSessionId(int sessionId) { return true; }
        @Override public int getAudioSessionId() { return 1; }
        @Override public void setReplayGain(float replayGain) {}
        @Override public void setDuckingFactor(float factor) {}
    }
    @Test
    public void recycledIdCannotOpenDifferentFile() {
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/unrelated.mp3";
        ShadowContentResolver.registerProviderInternal("media", provider);
        assertEquals(PlaybackSourceResolver.Status.MISMATCH,
                PlaybackSourceResolver.resolve(RuntimeEnvironment.getApplication(), expected()).status);
    }

    @Test
    public void exactPathCanResolveChangedIdAndKeepsExpectedMetadata() {
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/a.mp3";
        provider.id = 99;
        provider.directMissing = true;
        provider.size = -1;
        ShadowContentResolver.registerProviderInternal("media", provider);
        PlaybackSourceResolver.Resolution result =
                PlaybackSourceResolver.resolve(RuntimeEnvironment.getApplication(), expected());
        assertEquals(PlaybackSourceResolver.Status.VERIFIED, result.status);
        assertEquals("content://media/external_primary/audio/media/99", result.uri);
        assertEquals(99, result.song.id);
    }

    @Test public void samePathChangedVersionIsNotOpenedWithStaleMetadata() {
        Song expected = expected();
        expected.sourceSize = -1;
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/a.mp3";
        provider.directMissing = true;
        provider.size = 999;
        ShadowContentResolver.registerProviderInternal("media", provider);
        assertEquals(PlaybackSourceResolver.Status.UNAVAILABLE,
                PlaybackSourceResolver.resolve(RuntimeEnvironment.getApplication(), expected).status);
    }

    @Test
    public void ambiguousPathAndPermissionFailureNeverVerify() {
        SourceProvider provider = new SourceProvider();
        provider.path = "/Music/a.mp3";
        provider.directMissing = true;
        provider.duplicates = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        assertEquals(PlaybackSourceResolver.Status.MISMATCH,
                PlaybackSourceResolver.resolve(RuntimeEnvironment.getApplication(), expected()).status);
        provider.fail = true;
        assertEquals(PlaybackSourceResolver.Status.UNAVAILABLE,
                PlaybackSourceResolver.resolve(RuntimeEnvironment.getApplication(), expected()).status);
    }

    private static Song expected() {
        Song song = new Song(42, "Expected", 1, 2024, 1000, "/Music/a.mp3", 1, 2,
                7, "Album", new ArrayList<>(List.of("Artist")));
        song.contentUri = "content://media/external_primary/audio/media/42";
        return song;
    }

    public static class SourceProvider extends ContentProvider {
        String path;
        long id = 42;
        long size = -1;
        boolean directMissing, duplicates, fail;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            if (fail) throw new SecurityException("Permission lost");
            MatrixCursor cursor = new MatrixCursor(projection);
            if (selection == null && directMissing) return cursor;
            if (selection != null && !path.equals(args[0])) return cursor;
            Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                switch (projection[i]) {
                    case "_id": row[i] = id; break;
                    case "_data": row[i] = path; break;
                    case "volume_name": row[i] = "external_primary"; break;
                    case "_size": row[i] = size; break;
                    case "generation_modified": row[i] = -1L; break;
                    case "generation_added": row[i] = -1L; break;
                    case "title": row[i] = "Expected"; break;
                    case "artist": row[i] = "Artist"; break;
                    case "album": row[i] = "Album"; break;
                    case "album_id": row[i] = 7L; break;
                    case "duration": row[i] = 1000L; break;
                    case "date_added": row[i] = 1L; break;
                    case "date_modified": row[i] = 2L; break;
                    default: throw new AssertionError(projection[i]);
                }
            }
            cursor.addRow(row);
            if (duplicates) cursor.addRow(row);
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
