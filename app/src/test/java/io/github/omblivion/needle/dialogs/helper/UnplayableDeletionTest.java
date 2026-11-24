package io.github.omblivion.needle.dialogs.helper;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import androidx.fragment.app.FragmentActivity;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableDeletionTest {
    public static class PreparingFragment extends DeleteSongsDialogApi30 {
        @Override void deleteSongs(List<Song> songs) {
            // Preparation is still in flight.
        }
    }
    public static class DeleteFragment extends DeleteSongsDialogApi30 {
        boolean requested;
        @Override void deleteSongs(List<Song> songs) {
            // The external confirmation UI is outside this result-handling test.
            onDeleteRequestPrepared(PendingIntent.getActivity(requireContext(), 0,
                    new Intent("test.confirm.delete"), PendingIntent.FLAG_IMMUTABLE));
        }
        @Override void launchConfirmation(PendingIntent request) {
            requested = true;
        }
    }

    private UnplayableTrackStore store;
    private Song first;
    private Song second;

    @Before
    public void setUp() {
        store = UnplayableTrackStore.getInstance(RuntimeEnvironment.getApplication());
        store.getWritableDatabase().delete("reports", null, null);
        first = new Song(42, "Broken", 0, 0, 20000, "/music/broken.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        second = new Song(43, "Other", 0, 0, 20000, "/music/other.mp3",
                0, 0, 1, "Album", List.of("Artist"));
        store.recordFailure(first);
        store.recordFailure(second);
    }

    private DeleteFragment attach(FragmentActivity activity) {
        DeleteFragment fragment = new DeleteFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList("songs", new ArrayList<>(List.of(first)));
        fragment.setArguments(args);
        activity.getSupportFragmentManager().beginTransaction().add(fragment, "delete").commitNow();
        return fragment;
    }

    private void complete(DeleteFragment fragment, int resultCode) {
        fragment.handleDeleteResult(resultCode);
        Future<?> cleanup = ReflectionHelpers.getField(fragment, "reportCleanup");
        if (cleanup != null) {
            try {
                cleanup.get(10, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }

    @Test
    public void cancellationPreservesAllReports() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            complete(attach(controller.get()), Activity.RESULT_CANCELED);
            assertTrue(store.contains(first));
            assertTrue(store.contains(second));
        }
    }

    @Test
    public void confirmationRemovesOnlyTheSelectedReport() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            complete(attach(controller.get()), Activity.RESULT_OK);
            assertFalse(store.contains(first));
            assertTrue(store.contains(second));
        }
    }

    @Test
    public void restoringAPendingRequestDoesNotStartAnotherDeletionTask() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            DeleteFragment firstFragment = attach(activity);
            var manager = activity.getSupportFragmentManager();
            var saved = manager.saveFragmentInstanceState(firstFragment);
            manager.beginTransaction().remove(firstFragment).commitNow();
            DeleteFragment restored = new DeleteFragment();
            restored.setArguments(firstFragment.getArguments());
            restored.setInitialSavedState(saved);
            manager.beginTransaction().add(restored, "restored").commitNow();
            assertFalse(restored.requested);
            assertTrue(store.contains(first));
        }
    }

    @Test
    public void cancelledHelperIsRemovedSoRecreationCannotRepeatItsRequest() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            DeleteFragment fragment = attach(activity);
            complete(fragment, Activity.RESULT_CANCELED);
            activity.getSupportFragmentManager().executePendingTransactions();
            assertNull(activity.getSupportFragmentManager().findFragmentByTag("delete"));
            assertTrue(store.contains(first));
        }
    }

    @Test
    public void successfulHelperIsRemovedSoRecreationCannotRepeatItsRequest() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            DeleteFragment fragment = attach(activity);
            complete(fragment, Activity.RESULT_OK);
            activity.getSupportFragmentManager().executePendingTransactions();
            assertNull(activity.getSupportFragmentManager().findFragmentByTag("delete"));
            assertFalse(store.contains(first));
        }
    }

    @Test
    public void scopedStorageDeletionReachesConfirmationWithoutFolderPermission() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            DeleteFragment fragment = attach(controller.get());
            assertTrue(fragment.requested);
        }
    }

    @Test
    public void confirmedDeletionUpdatesReportsWithoutBlockingTheUiThread() throws Exception {
        CountDownLatch removed = new CountDownLatch(1);
        boolean[] onMainThread = new boolean[1];
        UnplayableTrackStore observed = new UnplayableTrackStore(RuntimeEnvironment.getApplication()) {
            @Override public boolean remove(Song song) {
                onMainThread[0] = Looper.myLooper() == Looper.getMainLooper();
                boolean result = store.remove(song);
                removed.countDown();
                return result;
            }
        };
        ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", observed);
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            complete(attach(controller.get()), Activity.RESULT_OK);
            assertTrue(removed.await(10, TimeUnit.SECONDS));
            assertFalse("Report cleanup must run off the UI thread", onMainThread[0]);
            assertFalse(store.contains(first));
        } finally {
            ReflectionHelpers.setStaticField(UnplayableTrackStore.class, "instance", store);
            observed.close();
        }
    }

    @Test
    public void failedPreparationWhileStateIsSavedFinishesWhenTheActivityRestarts() {
        try (var controller = Robolectric.buildActivity(FragmentActivity.class).setup()) {
            FragmentActivity activity = controller.get();
            PreparingFragment fragment = new PreparingFragment();
            Bundle args = new Bundle();
            args.putParcelableArrayList("songs", new ArrayList<>(List.of(first)));
            fragment.setArguments(args);
            activity.getSupportFragmentManager().beginTransaction().add(fragment, "pending").commitNow();
            controller.pause().saveInstanceState(new Bundle()).stop();
            fragment.onDeleteRequestPrepared(null);
            controller.restart().start().resume();
            activity.getSupportFragmentManager().executePendingTransactions();
            assertNull(activity.getSupportFragmentManager().findFragmentByTag("pending"));
            assertTrue(store.contains(first));
        }
    }
}
