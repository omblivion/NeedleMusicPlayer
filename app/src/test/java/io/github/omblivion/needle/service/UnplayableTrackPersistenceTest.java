package io.github.omblivion.needle.service;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTrackPersistenceTest {
    private UnplayableTrackStore store;
    private ProbeService service;
    private String actualPath = "/music/broken.mp3";

    private static class FailingStore extends UnplayableTrackStore {
        boolean failRead;
        boolean failRemove;
        private final UnplayableTrackStore delegate;
        FailingStore(UnplayableTrackStore delegate) {
            super(RuntimeEnvironment.getApplication());
            this.delegate = delegate;
        }
        @Override public boolean contains(Song song) {
            if (failRead) throw new SQLiteException("Simulated reporting read failure");
            return delegate.contains(song);
        }
        @Override public boolean remove(Song song) {
            if (failRemove) throw new SQLiteException("Simulated reporting write failure");
            return delegate.remove(song);
        }
    }

    private static class ProbeService extends MusicService {
        int progress;
        boolean playing = true;
        boolean failOpen;
        int skipRequests;
        Song testSong;

        @Override public Song getCurrentSong() {
            return testSong != null ? testSong : super.getCurrentSong();
        }
        @Override public int getSongProgressMillis() {return progress;}
        @Override public boolean isPlaying() {return playing;}
        @Override void requestSkipOfUnplayableTrack() {skipRequests++;}
        @Override void notifyChange(String what) {}
        @Override public void play() {}
        @Override boolean openTrackAndPrepareNextAt(int position) {
            setPositionForTest(position);
            return !failOpen || position != 0;
        }
    }

    @Before
    public void setUp() {
        store = UnplayableTrackStore.getInstance(RuntimeEnvironment.getApplication());
        store.getWritableDatabase().delete("reports", null, null);
        service = new ProbeService();
        service.openQueueForTest(new String[]{"/music/broken.mp3", "/music/good.mp3"}, 0);
        ContentProvider media = new ContentProvider() {
            @Override public boolean onCreate() {return true;}
            @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
                MatrixCursor cursor = new MatrixCursor(new String[]{"_data"});
                cursor.addRow(new Object[]{actualPath});
                return cursor;
            }
            @Override public String getType(Uri uri) {return "audio/mpeg";}
            @Override public Uri insert(Uri uri, ContentValues values) {throw new UnsupportedOperationException();}
            @Override public int delete(Uri uri, String selection, String[] args) {throw new UnsupportedOperationException();}
            @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {throw new UnsupportedOperationException();}
        };
        media.attachInfo(RuntimeEnvironment.getApplication(), null);
        ShadowContentResolver.registerProviderInternal("media", media);
    }

    @After
    public void restoreStore() {
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", store);
    }

    private void advancePlayback(int milliseconds) {
        for (int elapsed = 0; elapsed < milliseconds; elapsed += 250) {
            service.progress += 250;
            ShadowSystemClock.advanceBy(Duration.ofMillis(250));
            service.checkForRecoveredTrack();
        }
    }

    @Test
    public void runtimeFailurePersistsTheFailedSongAndStillRequestsSkip() {
        Song failed = service.getCurrentSong();
        service.onTrackFailed();
        service.onTrackFailed();
        assertEquals(1, store.getSongs().size());
        assertEquals(failed.id, store.getSongs().get(0).id);
        assertEquals(failed.data, store.getSongs().get(0).data);
        assertEquals(2, service.skipRequests);
    }

    @Test
    public void failureToOpenIsPersistedBeforeAdvancingToTheNextSong() {
        Song failed = service.getCurrentSong();
        service.failOpen = true;
        service.playSongAtImpl(0);
        assertTrue(store.contains(failed));
        assertEquals(1, service.getPosition());
    }

    @Test
    public void persistentReportDoesNotBlacklistTheTrackInANewService() {
        service.onTrackFailed();
        ProbeService restarted = new ProbeService();
        restarted.openQueueForTest(new String[]{"/music/broken.mp3"}, 0);
        assertFalse(restarted.isTrackUnplayable(0));
        assertEquals(1, store.getSongs().size());
    }

    @Test
    public void advancingPlaybackClearsTheReportAfterTheRecoveryInterval() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(10000);
        assertFalse(store.contains(song));
    }

    @Test
    public void preparationAloneDoesNotClearAReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void seekingWithoutElapsedPlaybackDoesNotClearAReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        service.progress = 10000;
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void pausedOrFailedPlaybackDoesNotClearAReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        service.progress = 10000;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        service.playing = false;
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
        service.playing = true;
        service.onTrackFailed();
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void switchingTracksDoesNotClearThePreviousReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        service.setPositionForTest(1);
        service.progress = 10000;
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void stalledPlaybackDoesNotRecoverAfterOneSmallPositionChange() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        service.progress = 1;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void aLateSeekDoesNotCountAsTenSecondsOfPlayback() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        service.progress = 90000;
        service.checkForRecoveredTrack();
        assertTrue(store.contains(song));
    }

    @Test
    public void reusedMediaIdDoesNotClearTheOriginalFilesReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        actualPath = "/music/replacement.mp3";
        advancePlayback(10000);
        assertTrue(store.contains(song));
    }

    @Test
    public void reportingReadFailureDoesNotInterruptPlayback() {
        FailingStore broken = new FailingStore(store);
        broken.failRead = true;
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", broken);
        service.scheduleUnplayableTrackRecovery();
        assertTrue(service.isPlaying());
    }

    @Test
    public void reportingWriteFailureDoesNotInterruptPlaybackOrLoseTheReport() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        FailingStore broken = new FailingStore(store);
        broken.failRemove = true;
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", broken);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(10000);
        assertTrue(service.isPlaying());
        assertTrue(store.contains(song));
    }

    @Test
    public void aShortTrackRecoversAfterHalfItsDurationActuallyAdvances() {
        service.testSong = new Song(1, "Short", 0, 0, 4000, "/music/broken.mp3",
                0, 0, 1, "Album", java.util.List.of("Artist"));
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(1750);
        assertTrue(store.contains(song));
        advancePlayback(250);
        assertFalse(store.contains(song));
    }

    @Test
    public void gaplessTrackChangeStartsANewRecoveryObservation() {
        Song firstSong = service.getCurrentSong();
        store.recordFailure(firstSong);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(5000);
        service.setPositionForTest(1);
        service.progress = 0;
        actualPath = "/music/good.mp3";
        Song secondSong = service.getCurrentSong();
        store.recordFailure(secondSong);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(10000);
        assertTrue(store.contains(firstSong));
        assertFalse(store.contains(secondSong));
    }

    @Test
    public void pausedTimeDoesNotCountTowardRecoveryAfterResuming() {
        Song song = service.getCurrentSong();
        store.recordFailure(song);
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(5000);
        service.playing = false;
        service.scheduleUnplayableTrackRecovery();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
        service.playing = true;
        service.scheduleUnplayableTrackRecovery();
        advancePlayback(5000);
        assertTrue(store.contains(song));
        advancePlayback(5000);
        assertFalse(store.contains(song));
    }
}
