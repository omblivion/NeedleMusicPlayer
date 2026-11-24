package io.github.omblivion.needle.discog;

import io.github.omblivion.needle.model.Song;
import java.io.File;
import java.util.function.Function;

/** Metadata belongs to one observed file version, never a partially copied successor. */
public final class MetadataReadResult {
    public enum Status { SUCCESS, UNSUPPORTED, RETRYABLE_FAILURE, UNSTABLE }
    public final Status status;
    public final Song song;

    public MetadataReadResult(Status status, Song song) {
        this.status = status;
        this.song = new Song(song);
        this.song.metadataRetryNeeded = status == Status.RETRYABLE_FAILURE || status == Status.UNSTABLE;
    }

    public static MetadataReadResult readStable(Song source, Function<Song, Status> reader) {
        File file = new File(source.data);
        if (!file.isFile() || !file.canRead()) return new MetadataReadResult(Status.RETRYABLE_FAILURE, source);
        long size = file.length();
        long modified = file.lastModified();
        Song detached = new Song(source);
        Status status = reader.apply(detached);
        if (!file.isFile() || size != file.length() || modified != file.lastModified()) {
            return new MetadataReadResult(Status.UNSTABLE, source);
        }
        return new MetadataReadResult(status, status == Status.SUCCESS ? detached : source);
    }
}
