package io.github.omblivion.needle.discog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Absence is evidence only when both provider and filesystem coverage are healthy. */
final class LibraryRemovalTracker {
    private final Map<Long, Long> firstAbsent = new HashMap<>();

    Set<Long> observe(Set<Long> absent, boolean complete, long now) {
        if (!complete) {
            firstAbsent.clear();
            return Set.of();
        }
        firstAbsent.keySet().retainAll(absent);
        Set<Long> confirmed = new HashSet<>();
        for (Long id : absent) {
            Long first = firstAbsent.putIfAbsent(id, now);
            if (first != null && now - first >= 2_000) confirmed.add(id);
        }
        return confirmed;
    }

    boolean hasPending() { return !firstAbsent.isEmpty(); }
}
