package io.github.omblivion.needle.service;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, for this session only, which tracks failed to play.
 *
 * <p>Nothing is persisted: a file that fails once may become readable later,
 * so it is retried after the app restarts. The registry is keyed by the same
 * URI string handed to the player, which avoids the {@code -1} id sentinel
 * that several "no song" placeholders share.
 */
public class UnplayableTrackRegistry {
    private final Set<String> unplayableUris =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Records {@code uri} as unplayable.
     *
     * @return true when it was not already recorded
     */
    public boolean markUnplayable(@Nullable final String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        return unplayableUris.add(uri);
    }

    public boolean isUnplayable(@Nullable final String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        return unplayableUris.contains(uri);
    }

    public void clear() {
        unplayableUris.clear();
    }
}
