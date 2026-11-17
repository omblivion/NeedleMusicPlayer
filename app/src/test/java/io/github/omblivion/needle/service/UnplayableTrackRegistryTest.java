package io.github.omblivion.needle.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class UnplayableTrackRegistryTest {
    @Test
    public void marksAndReportsUris() {
        UnplayableTrackRegistry registry = new UnplayableTrackRegistry();
        registry.markUnplayable("content://media/1");
        assertTrue(registry.isUnplayable("content://media/1"));
        assertEquals(false, registry.isUnplayable("content://media/2"));
    }

    @Test
    public void ignoresNullAndEmptyUris() {
        UnplayableTrackRegistry registry = new UnplayableTrackRegistry();
        registry.markUnplayable(null);
        registry.markUnplayable("");
        assertEquals(false, registry.isUnplayable(null));
        assertEquals(false, registry.isUnplayable(""));
    }
}
