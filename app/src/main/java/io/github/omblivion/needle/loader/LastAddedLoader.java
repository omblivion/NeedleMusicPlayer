package io.github.omblivion.needle.loader;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.sort.SongSortOrder;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.Comparator;

public class LastAddedLoader {

    @NonNull
    public static ArrayList<Song> getLastAddedSongs() {
        long cutoff = PreferenceUtil.getInstance().getLastAddedCutoffTimeSecs();

        ArrayList<Song> lastAddedSongs = new ArrayList<>();
        @NonNull final String sortOrderStr = PreferenceUtil.getInstance().getLastAddedSortOrder();
        Comparator<Song> sortOrder = sortOrderStr.equals(PreferenceUtil.ALBUM_SORT_ORDER) ? SongSortOrder.BY_ALBUM_DATE_ADDED_DESC : SongSortOrder.BY_DATE_ADDED_DESC;
        for (Song song : Discography.getInstance().getAllSongs(sortOrder)) {
            if (song.dateAdded > cutoff) {lastAddedSongs.add(song);}
        }
        return lastAddedSongs;
    }
}
