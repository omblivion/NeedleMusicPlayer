package io.github.omblivion.needle.service;

import static org.junit.Assert.fail;

import io.github.omblivion.needle.App;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = App.class)
public class CoverArtDecodeTest {
    /**
     * A stream that throws part-way through being read, exactly what a
     * truncated or corrupt embedded cover does.
     */
    private static class ExplodingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("truncated cover art");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("truncated cover art");
        }
    }

    @Test
    public void aTruncatedCoverDoesNotPropagateAnException() {
        try {
            MusicService.decodeCoverArtSafely(new ExplodingInputStream());
        } catch (Throwable t) {
            fail("a corrupt cover must degrade to no artwork, not throw: " + t);
        }
    }
}
