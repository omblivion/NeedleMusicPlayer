package io.github.omblivion.needle.discog;

import io.github.omblivion.needle.provider.ReferenceKey;

import java.util.Map;
import java.util.Set;

/** Immutable result of resolving stored references against the new catalog. */
public final class ReferencePlan {
    /** Stored key to the key of the track now at the same path. */
    public final Map<ReferenceKey, ReferenceKey> relocations;
    /** Stored keys whose path was confirmed deleted. */
    public final Set<ReferenceKey> deletedKeys;

    public ReferencePlan(Map<ReferenceKey, ReferenceKey> relocations, Set<ReferenceKey> deletedKeys) {
        this.relocations = Map.copyOf(relocations);
        this.deletedKeys = Set.copyOf(deletedKeys);
    }

    public boolean isEmpty() {
        return relocations.isEmpty() && deletedKeys.isEmpty();
    }
}
