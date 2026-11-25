package io.github.omblivion.needle.loader;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.model.Artist;
import io.github.omblivion.needle.sort.ArtistSortOrder;
import io.github.omblivion.needle.sort.SortOrder;
import io.github.omblivion.needle.util.PreferenceUtil;
import io.github.omblivion.needle.util.StringUtil;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * @author Karim Abou Zeid (kabouzeid)
 * @author SC (soncaokim)
 */
public class ArtistLoader {
    @NonNull
    public static ArrayList<Artist> getAllArtists() {
        return Discography.getInstance().getAllArtists(getSortOrder());
    }

    @NonNull
    public static ArrayList<Artist> getArtists(String query) {
        final String strippedQuery = StringUtil.normalizeForSearch(query);

        ArrayList<Artist> artists = new ArrayList<>();
        for (Artist artist : Discography.getInstance().getAllArtists(null)) {
            final String strippedArtist = StringUtil.normalizeForSearch(artist.getName());
            if (strippedArtist.contains(strippedQuery)) {
                artists.add(artist);
            }
        }
        artists.sort(getSortOrder());
        return artists;
    }

    @NonNull
    public static Artist getArtist(long artistId) {
        Artist artist = Discography.getInstance().getArtist(artistId);
        if (artist != null) {
            return artist;
        } else {
            return Artist.EMPTY;
        }
    }

    @NonNull
    private static Comparator<Artist> getSortOrder() {
        SortOrder<Artist> sortOrder = ArtistSortOrder.fromPreference(PreferenceUtil.getInstance().getArtistSortOrder());
        return sortOrder.comparator;
    }
}
