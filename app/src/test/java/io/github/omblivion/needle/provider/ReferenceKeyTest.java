package io.github.omblivion.needle.provider;

import static org.junit.Assert.*;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class ReferenceKeyTest {
    @Test public void bareIdParsesWithEmptyPath() {
        ReferenceKey key = ReferenceKey.parse("42");
        assertEquals(42, key.id());
        assertEquals("", key.path());
        assertEquals("42", key.serialize());
    }

    @Test public void pathRoundTripsEvenWhenItContainsSeparators() {
        String path = io.github.omblivion.needle.model.SongSource.canonicalPath("/Music/a:b/c.mp3");
        ReferenceKey key = ReferenceKey.parse("42:" + path);
        assertEquals(42, key.id());
        assertEquals(path, key.path());
        assertEquals("42:" + path, key.serialize());
    }

    @Test public void malformedTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceKey.parse("not-a-number"));
    }

    @Test public void idCanBeRefreshedWithoutLosingThePath() {
        String path = io.github.omblivion.needle.model.SongSource.canonicalPath("/Music/a.mp3");
        ReferenceKey key = ReferenceKey.parse("1:" + path).withId(9);
        assertEquals(9, key.id());
        assertEquals(path, key.path());
    }

    @Test public void equalityIsByIdAndPath() {
        String path = io.github.omblivion.needle.model.SongSource.canonicalPath("/Music/a.mp3");
        assertEquals(ReferenceKey.parse("1:" + path), ReferenceKey.parse("1:" + path));
        assertNotEquals(ReferenceKey.parse("1:" + path), ReferenceKey.parse("2:" + path));
    }
}
