package io.github.omblivion.needle.provider;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;

/**
 * A user reference to a track: the provider ID it currently has plus the
 * canonical path it belongs to. The path is the identity; the ID is only a
 * convenience that is refreshed whenever the catalog is published.
 */
public final class ReferenceKey {
    private final long id;
    @NonNull
    private final String path;

    public ReferenceKey(long id, @NonNull String path) {
        this.id = id;
        this.path = path.isEmpty() ? "" : SongSource.canonicalPath(path);
    }

    public long id() {
        return id;
    }

    @NonNull
    public String path() {
        return path;
    }

    @NonNull
    public ReferenceKey withId(long newId) {
        return new ReferenceKey(newId, path);
    }

    @NonNull
    public ReferenceKey withPath(@NonNull String newPath) {
        return new ReferenceKey(id, newPath);
    }

    @NonNull
    public static ReferenceKey of(@NonNull Song song) {
        return new ReferenceKey(song.id, song.data);
    }

    /**
     * Accepts a bare ID or an {@code id:path} token. Splitting on the first
     * colon keeps a Windows drive letter or an Android path intact.
     */
    @NonNull
    public static ReferenceKey parse(@NonNull String token) {
        int separator = token.indexOf(':');
        String idPart = separator < 0 ? token : token.substring(0, separator);
        String pathPart = separator < 0 ? "" : token.substring(separator + 1);
        try {
            return new ReferenceKey(Long.parseLong(idPart.trim()), pathPart);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("Malformed reference key: " + token, malformed);
        }
    }

    @NonNull
    public String serialize() {
        return path.isEmpty() ? Long.toString(id) : id + ":" + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReferenceKey)) return false;
        ReferenceKey other = (ReferenceKey) o;
        return id == other.id && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(id) + path.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return serialize();
    }
}
