package io.github.omblivion.needle.loader;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.sort.AlbumSortOrder;
import io.github.omblivion.needle.sort.SortOrder;
import io.github.omblivion.needle.util.StringUtil;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * @author Karim Abou Zeid (kabouzeid)
 * @author SC (soncaokim)
 */
public class AlbumLoader {
    @NonNull
    public static ArrayList<Album> getAllAlbums() {
        return Discography.getInstance().getAllAlbums(getSortOrder());
    }

    @NonNull
    public static ArrayList<Album> getAlbums(String query) {
        final String strippedQuery = StringUtil.normalizeForSearch(query);
        ArrayList<Album> albums = Discography.getInstance().getMatchingAlbums(
                title -> StringUtil.normalizeForSearch(title).contains(strippedQuery));
        albums.sort(getSortOrder());
        return albums;
    }

    @NonNull
    public static Album getAlbum(long albumId) {
        Album album = Discography.getInstance().getAlbum(albumId);
        if (album != null) {
            return album;
        } else {
            return new Album();
        }
    }

    @NonNull
    private static Comparator<Album> getSortOrder() {
        SortOrder<Album> sortOrder = AlbumSortOrder.fromPreference(PreferenceUtil.getInstance().getAlbumSortOrder());
        return sortOrder.comparator;
    }
}
