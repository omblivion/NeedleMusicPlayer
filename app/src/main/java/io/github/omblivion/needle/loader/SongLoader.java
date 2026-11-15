package io.github.omblivion.needle.loader;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.sort.SongSortOrder;
import io.github.omblivion.needle.sort.SortOrder;
import io.github.omblivion.needle.util.PreferenceUtil;
import io.github.omblivion.needle.util.StringUtil;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * @author Karim Abou Zeid (kabouzeid)
 * @author SC (soncaokim)
 */
public class SongLoader {
    @NonNull
    public static ArrayList<Song> getAllSongs() {
        return Discography.getInstance().getAllSongs(getSortOrder());
    }

    @NonNull
    public static ArrayList<Song> getSongs(@NonNull final String query) {
        final String strippedQuery = StringUtil.stripAccent(query.toLowerCase());

        ArrayList<Song> songs = new ArrayList<>();
        for (Song song : Discography.getInstance().getAllSongs(getSortOrder())) {
            final String strippedTitle = StringUtil.stripAccent(song.title.toLowerCase());
            if (strippedTitle.contains(strippedQuery)) {
                songs.add(song);
            }
        }
        return songs;
    }

    @NonNull
    public static Comparator<Song> getSortOrder() {
        SortOrder<Song> sortOrder = SongSortOrder.fromPreference(PreferenceUtil.getInstance().getSongSortOrder());
        return sortOrder.comparator;
    }
}
