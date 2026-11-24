package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class ReferenceReconciliationRecoveryTest {
    @org.junit.Before @org.junit.After public void resetStores() {
        ReflectionHelpers.setStaticField(HistoryStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(SongPlayCountStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(PreferencesBackedSongList.class, "preferences", null);
        ReflectionHelpers.setStaticField(MusicPlaybackQueueStore.class, "sInstance", null);
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", null);
        ReferenceReconciliationJournal.applyGate = ReferenceReconciliationJournal.ApplyGate.NONE;
    }

    @Test public void interruptedSwapRecoversExactlyOnce() {
        Discography before = App.getDiscography();
        Song a = LibraryReferenceReconciliationTest.song(1, "a");
        Song b = LibraryReferenceReconciliationTest.song(2, "b");
        before.publishSnapshot(new LibrarySnapshot(List.of(a, b), 0));
        HistoryStore history = HistoryStore.getInstance(RuntimeEnvironment.getApplication());
        history.addSongId(1, "/Music/a.mp3");
        StaticPlaylist playlist = new StaticPlaylist("Keep");
        playlist.addSongs(List.of(a, b));

        // Two files exchange provider IDs; identity follows the path.
        Song movedA = LibraryReferenceReconciliationTest.song(2, "a");
        Song movedB = LibraryReferenceReconciliationTest.song(1, "b");
        ReferenceReconciliationJournal.applyGate = () -> {
            throw new IllegalStateException("Injected failure");
        };
        assertThrows(IllegalStateException.class,
                () -> before.publishSnapshot(new LibrarySnapshot(List.of(movedA, movedB), 1)));
        ReferenceReconciliationJournal.applyGate = ReferenceReconciliationJournal.ApplyGate.NONE;

        // The interrupted operation left a durable marker.
        try (ReferenceReconciliationJournal journal = new ReferenceReconciliationJournal()) {
            assertNotNull(journal.read());
        }

        Discography restarted = new Discography();
        ReflectionHelpers.setStaticField(App.class, "discography", restarted);
        // A normal reference read finishes recovery before accepting another edit.
        assertEquals(2, new StaticPlaylist("Keep").asSongs().get(0).id);
        assertTrue(history.contains(2));
        assertFalse(history.contains(1));

        // Recovery is idempotent: a second pass changes nothing and clears the marker.
        restarted.recoverReferences();
        assertEquals(2, new StaticPlaylist("Keep").asSongs().get(0).id);
        try (ReferenceReconciliationJournal journal = new ReferenceReconciliationJournal()) {
            assertNull(journal.read());
        }
    }

    @Test public void recoveryPublishesDeletionAndNotifiesOnce() {
        Discography discography = App.getDiscography();
        Song a = LibraryReferenceReconciliationTest.song(1, "a");
        discography.publishSnapshot(new LibrarySnapshot(List.of(a), 0));
        int[] notifications = {0};
        discography.addChangedListener(() -> notifications[0]++);
        ReferenceReconciliationJournal.applyGate = () -> {
            throw new IllegalStateException("Injected failure");
        };
        assertThrows(IllegalStateException.class, () -> discography.confirmDeletion(List.of(a)));
        ReferenceReconciliationJournal.applyGate = ReferenceReconciliationJournal.ApplyGate.NONE;

        // Recovery publishes the deletion and notifies exactly once.
        ReferenceReconciliationJournal.access(() -> null);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(250));
        assertTrue(discography.isConfirmedDeleted(a));
        assertEquals(1, notifications[0]);
    }
}
