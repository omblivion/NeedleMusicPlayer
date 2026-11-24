package io.github.omblivion.needle.discog;

import static org.junit.Assert.*;
import io.github.omblivion.needle.App;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class LibraryReconciliationTest {
    @Test public void removalRequiresTwoCompleteAbsentPassesAtLeastTwoSecondsApart() {
        LibraryRemovalTracker tracker = new LibraryRemovalTracker();
        assertTrue(tracker.observe(Set.of(42L), true, 100).isEmpty());
        assertTrue(tracker.observe(Set.of(42L), true, 2099).isEmpty());
        assertEquals(Set.of(42L), tracker.observe(Set.of(42L), true, 2100));
    }
    @Test public void failureAndReappearanceResetEvidence() {
        LibraryRemovalTracker tracker = new LibraryRemovalTracker();
        tracker.observe(Set.of(42L), true, 100);
        assertTrue(tracker.observe(Set.of(42L), false, 2100).isEmpty());
        assertTrue(tracker.observe(Set.of(42L), true, 4100).isEmpty());
        tracker.observe(Set.of(), true, 5100);
        assertTrue(tracker.observe(Set.of(42L), true, 6100).isEmpty());
    }
}
