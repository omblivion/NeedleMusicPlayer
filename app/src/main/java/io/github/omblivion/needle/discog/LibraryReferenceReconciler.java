package io.github.omblivion.needle.discog;

import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.provider.ReferenceKey;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A track is its canonical path, so a stored reference is resolved by looking
 * its path up in the catalog. There is no ID mapping, no sentinel, and no
 * similarity match: an absent path simply leaves the reference untouched.
 */
public final class LibraryReferenceReconciler {
    private LibraryReferenceReconciler() {}

    /** The song now at {@code storedPath}, or {@code null} when nothing matches. */
    public static Song resolvePath(String storedPath, Map<Long, Song> next) {
        if (storedPath == null || storedPath.isEmpty()) return null;
        String canonical = SongSource.canonicalPath(storedPath);
        for (Song song : next.values()) {
            if (SongSource.canonicalPath(song.data).equals(canonical)) return song;
        }
        return null;
    }

    public static ReferencePlan derive(Collection<ReferenceKey> stored, Map<Long, Song> next,
            Set<String> confirmedDeletedPaths) {
        Set<String> deletedPaths = new HashSet<>();
        for (String path : confirmedDeletedPaths) {
            deletedPaths.add(SongSource.canonicalPath(path));
        }
        Map<ReferenceKey, ReferenceKey> relocations = new HashMap<>();
        Set<ReferenceKey> deleted = new HashSet<>();
        for (ReferenceKey key : stored) {
            if (key.path().isEmpty()) continue;
            String path = SongSource.canonicalPath(key.path());
            if (deletedPaths.contains(path)) {
                deleted.add(key);
                continue;
            }
            Song resolved = resolvePath(path, next);
            if (resolved != null && resolved.id != key.id()) {
                relocations.put(key, key.withId(resolved.id));
            }
        }
        return new ReferencePlan(relocations, deleted);
    }
}
