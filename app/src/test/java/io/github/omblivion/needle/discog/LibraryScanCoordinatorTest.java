package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryScanCoordinatorTest {
    @Test public void indexingBarrierCoalescesRequestsAndPreservesRebuildFlag() {
        Backend backend = new Backend();
        ArrayList<Runnable> worker = new ArrayList<>();
        ArrayList<Runnable> deadlines = new ArrayList<>();
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(backend,
                worker::add, (r, delay) -> deadlines.add(r));
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(false, List.of()));
        worker.remove(0).run();
        assertEquals(0, backend.completed.size());
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(false, List.of()));
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(true, List.of()));
        backend.callbacks.get(0).complete("a", true);
        backend.callbacks.get(0).complete("a", true);
        assertEquals(0, backend.completed.size());
        backend.callbacks.get(0).complete("b", true);
        worker.remove(0).run();
        assertEquals(List.of(false), backend.completed);
        worker.remove(0).run();
        assertTrue(backend.requests.get(1).forceRebuild);
        assertEquals(2, backend.requests.size());
    }

    @Test public void callbackDeadlineMarksPassIncompleteAndLateCallbacksDoNothing() {
        Backend backend = new Backend();
        ArrayList<Runnable> deadlines = new ArrayList<>();
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(backend,
                Runnable::run, (r, delay) -> deadlines.add(r));
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(false, List.of()));
        deadlines.get(0).run();
        assertEquals(List.of(true), backend.incomplete);
        backend.callbacks.get(0).complete("a", true);
        backend.callbacks.get(0).complete("b", true);
        assertEquals(1, backend.completed.size());
    }

    private static class Backend implements LibraryScanCoordinator.Backend {
        final List<LibraryScanCoordinator.ScanRequest> requests = new ArrayList<>();
        final List<LibraryScanCoordinator.IndexCallback> callbacks = new ArrayList<>();
        final List<Boolean> completed = new ArrayList<>();
        final List<Boolean> incomplete = new ArrayList<>();
        @Override public List<String> discover(LibraryScanCoordinator.ScanRequest request) {
            requests.add(request);
            return List.of("a", "b");
        }
        @Override public void index(List<String> paths, LibraryScanCoordinator.IndexCallback callback) {
            callbacks.add(callback);
        }
        @Override public void reconcile(LibraryScanCoordinator.ScanRequest request, boolean complete) {
            completed.add(request.forceRebuild);
            incomplete.add(!complete);
        }
    }
}
