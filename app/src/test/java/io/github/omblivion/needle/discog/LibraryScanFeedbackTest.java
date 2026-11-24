package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryScanFeedbackTest {
    @Test public void failedDiscoveryReportsFailureAndDoesNotBlockNextRequest() {
        int[] failed = {0};
        int[] completed = {0};
        LibraryScanCoordinator.Backend backend = new LibraryScanCoordinator.Backend() {
            boolean first = true;
            @Override public List<String> discover(LibraryScanCoordinator.ScanRequest request) {
                if (first) {
                    first = false;
                    throw new SecurityException("Root inaccessible");
                }
                return List.of();
            }
            @Override public void index(List<String> paths, LibraryScanCoordinator.IndexCallback callback) {
                fail("No files to index");
            }
            @Override public void reconcile(LibraryScanCoordinator.ScanRequest request, boolean complete) {
                completed[0]++;
            }
            @Override public void failed(LibraryScanCoordinator.ScanRequest request) {
                failed[0]++;
            }
        };
        LibraryScanCoordinator coordinator = new LibraryScanCoordinator(backend, Runnable::run,
                (r, delay) -> fail("No timeout needed"));
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(true, List.of()));
        assertEquals(1, failed[0]);
        assertEquals(0, completed[0]);
        coordinator.requestScan(new LibraryScanCoordinator.ScanRequest(true, List.of()));
        assertEquals(1, completed[0]);
    }
}
