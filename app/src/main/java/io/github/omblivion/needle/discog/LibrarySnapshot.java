package io.github.omblivion.needle.discog;

import io.github.omblivion.needle.model.Song;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Detached candidate catalog. It becomes visible only after its database commit. */
public final class LibrarySnapshot {
    final Map<Long, Song> songs;
    final MemCache prepared = new MemCache();
    final long baseRevision;
    final Set<Long> confirmedDeleted;
    final Set<String> confirmedDeletedPaths;

    public LibrarySnapshot(Collection<Song> songs, long baseRevision) {
        this(songs, baseRevision, Set.of(), Set.of());
    }

    LibrarySnapshot(Collection<Song> songs, long baseRevision, Set<Long> confirmedDeleted) {
        this(songs, baseRevision, confirmedDeleted, Set.of());
    }

    LibrarySnapshot(Collection<Song> songs, long baseRevision, Set<Long> confirmedDeleted,
            Set<String> confirmedDeletedPaths) {
        Map<Long, Song> detached = new LinkedHashMap<>();
        for (Song song : songs) {
            if (detached.put(song.id, new Song(song)) != null) {
                throw new IllegalArgumentException("Duplicate provider identity in snapshot");
            }
        }
        this.songs = Collections.unmodifiableMap(detached);
        this.baseRevision = baseRevision;
        this.confirmedDeleted = Set.copyOf(confirmedDeleted);
        this.confirmedDeletedPaths = Set.copyOf(confirmedDeletedPaths);
        for (Song song : detached.values()) prepared.addSong(song);
    }
}
