package io.github.omblivion.needle.service;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.util.MusicUtil;

/** Validates the path behind an ID before allowing it to open audio. */
public final class PlaybackSourceResolver {
    public enum Status { VERIFIED, UNAVAILABLE, MISMATCH }

    public static final class Resolution {
        public final Status status;
        @Nullable public final Song song;
        @Nullable public final String uri;

        private Resolution(Status status, @Nullable Song song, @Nullable String uri) {
            this.status = status;
            this.song = song;
            this.uri = uri;
        }
    }

    private static final String[] PROJECTION = {
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA,
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
    };

    private PlaybackSourceResolver() {}

    @NonNull
    public static Resolution resolve(@NonNull Context context, @NonNull Song expected) {
        if (expected.id == Song.EMPTY_SONG.id || expected.data.isEmpty()) {
            return new Resolution(Status.UNAVAILABLE, null, null);
        }
        String expectedPath = SongSource.canonicalPath(expected.data);
        Uri uri = expected.contentUri.isEmpty() ? MusicUtil.getSongFileUri(expected.id)
                : Uri.parse(expected.contentUri);
        try {
            boolean mismatch = false;
            try (Cursor cursor = context.getContentResolver().query(uri, PROJECTION,
                    null, null, null)) {
                if (cursor == null) return new Resolution(Status.UNAVAILABLE, null, null);
                if (cursor.getCount() == 1 && cursor.moveToFirst()) {
                    Resolution direct = verify(cursor, expected, expectedPath);
                    if (direct.status == Status.VERIFIED) return direct;
                    mismatch = true;
                } else if (cursor.getCount() > 1) {
                    mismatch = true;
                }
            }
            // Exact full-path matching only; never infer identity from tags or basename.
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION,
                    MediaStore.Audio.Media.DATA + "=?", new String[]{expected.data}, null)) {
                if (cursor == null) return new Resolution(Status.UNAVAILABLE, null, null);
                if (cursor.getCount() > 1) return new Resolution(Status.MISMATCH, null, null);
                if (cursor.moveToFirst()) return verify(cursor, expected, expectedPath);
            }
            return new Resolution(mismatch ? Status.MISMATCH : Status.UNAVAILABLE, null, null);
        } catch (RuntimeException inaccessible) {
            return new Resolution(Status.UNAVAILABLE, null, null);
        }
    }

    private static Resolution verify(Cursor cursor, Song expected, String expectedPath) {
        String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
        if (path == null || !expectedPath.equals(SongSource.canonicalPath(path))) {
            return new Resolution(Status.MISMATCH, null, null);
        }
        String volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME));
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        Uri collection = volume == null || volume.isEmpty()
                ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.getContentUri(volume);
        String uri = ContentUris.withAppendedId(collection, id).toString();
        Song verified = new Song(expected, id);
        verified.contentUri = uri;
        verified.sourceSize = value(cursor, MediaStore.MediaColumns.SIZE, expected.sourceSize);
        verified.sourceGeneration = value(cursor, MediaStore.MediaColumns.GENERATION_MODIFIED, -1);
        verified.sourceAddedGeneration = value(cursor, MediaStore.MediaColumns.GENERATION_ADDED, -1);
        verified.albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
        String album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
        if (album != null && !album.isEmpty()) verified.albumName = album;
        String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
        if (title != null && !title.trim().isEmpty()) verified.title = title;
        String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
        if (artist != null && !artist.isEmpty()) {
            verified.artistNames = io.github.omblivion.needle.discog.tagging.MultiValuesTagUtil.split(artist);
        }
        SongSource expectedSource = SongSource.from(expected);
        SongSource verifiedSource = SongSource.from(verified);
        if (!expectedSource.sameTrack(verifiedSource)) {
            // The stored reference resolves to a different file; never open it.
            return new Resolution(Status.MISMATCH, null, null);
        }
        // Version fields invalidate cached metadata, not the identity of this
        // exact path. A rescan or an old catalog must not prevent opening it.
        if (expectedSource.versionChanged(verifiedSource)) {
            Song fresh = new Song(id, verified.title, expected.trackNumber, expected.year,
                    value(cursor, MediaStore.Audio.Media.DURATION, expected.duration), expected.data,
                    value(cursor, MediaStore.Audio.Media.DATE_ADDED, expected.dateAdded),
                    value(cursor, MediaStore.Audio.Media.DATE_MODIFIED, expected.dateModified),
                    verified.albumId, verified.albumName, verified.artistNames);
            fresh.contentUri = uri;
            fresh.sourceSize = verified.sourceSize;
            fresh.sourceGeneration = verified.sourceGeneration;
            fresh.sourceAddedGeneration = verified.sourceAddedGeneration;
            fresh.providerVersion = verified.providerVersion;
            verified = fresh;
        }
        return new Resolution(Status.VERIFIED, verified, uri);
    }

    private static long value(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? fallback : cursor.getLong(index);
    }
}
