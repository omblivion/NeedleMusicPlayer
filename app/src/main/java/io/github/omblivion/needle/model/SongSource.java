package io.github.omblivion.needle.model;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Immutable description of a locator and the observed version of its contents. */
public final class SongSource {
    public final String contentUri;
    public final String path;
    public final long size;
    public final long generation;
    public final long addedGeneration;
    public final String providerVersion;
    public final long dateAdded;
    public final long dateModified;

    private SongSource(Song song) {
        contentUri = song.contentUri;
        path = canonicalPath(song.data);
        size = song.sourceSize;
        generation = song.sourceGeneration;
        addedGeneration = song.sourceAddedGeneration;
        providerVersion = song.providerVersion;
        dateAdded = song.dateAdded;
        dateModified = song.dateModified;
    }

    @NonNull
    public static SongSource from(@NonNull Song song) {
        return new SongSource(song);
    }

    /** True when this is a usable, non-placeholder source identity. */
    public boolean isKnown() {
        return !path.isEmpty();
    }

    /**
     * The one identity rule: the canonical path is the track. A provider ID may
     * change when Android rescans, but the file's location is what the user
     * recognizes as the same track. Version fields only decide whether cached
     * metadata and preloaded audio are still current; they never decide identity.
     */
    public boolean sameTrack(@NonNull SongSource other) {
        return !path.isEmpty() && path.equals(other.path);
    }

    /**
     * True when the file content is known to differ from the other source.
     * A value known on one side is authoritative even when the other side
     * omitted it; two unknown values never count as a change.
     */
    public boolean versionChanged(@NonNull SongSource other) {
        if (size >= 0 && other.size >= 0) {
            if (size != other.size) return true;
        } else if (size < 0 && other.size >= 0) {
            // The catalog did not know the size; a provider that does cannot be
            // assumed to describe the same content.
            return true;
        }
        if (generation >= 0 && other.generation >= 0 && generation != other.generation) return true;
        if (!providerVersion.isEmpty() && !other.providerVersion.isEmpty()
                && !providerVersion.equals(other.providerVersion)) return true;
        return false;
    }

    @NonNull
    public static String canonicalPath(@NonNull String path) {
        if (path.isEmpty()) return "";
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException | SecurityException unavailable) {
            return new File(path).getAbsolutePath();
        }
    }
}
