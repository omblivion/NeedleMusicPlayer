package io.github.omblivion.needle.discog;

import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.discog.tagging.MultiValuesTagUtil;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Karim Abou Zeid (kabouzeid)
 * @author SC (soncaokim)
 */

public class MediaStoreBridge {
    private static final String[] BASE_PROJECTION = new String[]{
            BaseColumns._ID,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.Audio.AudioColumns.YEAR,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.AudioColumns.DATA,
            MediaStore.Audio.AudioColumns.DATE_ADDED,
            MediaStore.Audio.AudioColumns.DATE_MODIFIED,
            MediaStore.Audio.AudioColumns.ALBUM_ID,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.VOLUME_NAME,
    };

    @NonNull
    public static ArrayList<Song> getAllSongs(@NonNull Context context) {
        try (Cursor cursor = makeSongCursor(context)) {
            if (cursor == null) {
                throw new IllegalStateException("MediaStore query returned no cursor");
            }
            return getSongs(context, cursor);
        }
    }

    @NonNull
    private static ArrayList<Song> getSongs(Context context, @Nullable final Cursor cursor) {
        ArrayList<Song> songs = new ArrayList<>();
        Map<String, String> versions = new HashMap<>();
        if (cursor != null && cursor.moveToFirst()) {
            do {
                String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                // Some provider rows are inaccessible or have no filesystem locator.
                if (path == null || path.isEmpty()) continue;
                Song song = getSongFromCursorImpl(cursor, path);
                String volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME));
                if (volume != null && !volume.isEmpty()) {
                    song.providerVersion = versions.computeIfAbsent(volume, v -> {
                        try {
                            String version = MediaStore.getVersion(context, v);
                            return version == null ? "" : version;
                        } catch (RuntimeException unavailableVersion) {
                            // A version is an optional invalidation signal, not query completeness.
                            return "";
                        }
                    });
                }
                songs.add(song);
            } while (cursor.moveToNext());
        }
        return songs;
    }

    @NonNull
    private static Song getSongFromCursorImpl(@NonNull Cursor cursor, @NonNull String data) {
        // Most of the time data imported from these columns are overriden by those extracted ourselves from ID3 tags
        // However, in the case extraction fails (unsupported track format), they serve as fallback values
        final long id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TITLE));
        final int trackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK));
        final int year = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.YEAR));
        final long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION));
        if (title == null || title.trim().isEmpty()) title = new java.io.File(data).getName();
        final long dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_ADDED));
        final long dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_MODIFIED));
        final long albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID));
        final String albumName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM));
        final List<String> artistNames = MultiValuesTagUtil.split(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)));

        final Song song = new Song(id, title, trackNumber, year, duration, data, dateAdded, dateModified, albumId, albumName, artistNames);
        int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
        int generationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED);
        song.sourceSize = cursor.isNull(sizeColumn) ? -1 : cursor.getLong(sizeColumn);
        song.sourceGeneration = cursor.isNull(generationColumn) ? -1 : cursor.getLong(generationColumn);
        int addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED);
        song.sourceAddedGeneration = cursor.isNull(addedColumn) ? -1 : cursor.getLong(addedColumn);
        String volume = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME));
        song.contentUri = ContentUris.withAppendedId(
                volume == null || volume.isEmpty() ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Audio.Media.getContentUri(volume), id).toString();

        // MediaStore compat: Split track number into disc + track number
        // See documentation for MediaStore.Audio.AudioColumns.TRACK
        song.discNumber = trackNumber / 1000;
        song.trackNumber = trackNumber % 1000;

        return song;
    }

    @Nullable
    private static Cursor makeSongCursor(@NonNull final Context context) {
        return context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    BASE_PROJECTION,
                     null,
                    null,
                    PreferenceUtil.getInstance().getSongSortOrder()
        );
    }
}
