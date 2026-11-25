package io.github.omblivion.needle.model.smartplaylist;

import android.content.Context;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.loader.TopAndRecentlyPlayedTracksLoader;
import io.github.omblivion.needle.model.Playlist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.HistoryStore;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.PreferenceUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class HistoryPlaylist extends AbsSmartPlaylist {

    public HistoryPlaylist(@NonNull Context context) {
        super(context.getString(R.string.history), R.drawable.ic_access_time_white_24dp);
    }

    @NonNull
    @Override
    public String getInfoString(@NonNull final Context context) {
        final String cutoff = PreferenceUtil.getInstance().getRecentlyPlayedCutoffText(context);

        return MusicUtil.buildInfoString(
            cutoff,
            super.getInfoString(context)
        );
    }

    @Nullable
    @Override
    public String getPlaylistPreference() {
        return PreferenceUtil.RECENTLY_PLAYED_CUTOFF_V2;
    }

    @NonNull
    @Override
    public ArrayList<Song> getSongs(@NonNull Context context) {
        return TopAndRecentlyPlayedTracksLoader.getRecentlyPlayedTracks(context);
    }

    @Override
    public void clear(@NonNull Context context) {
        HistoryStore.getInstance(context).clear();
        super.clear(context);
    }

    @Override
    public boolean canImport() {return true;}

    @Override
    public void importPlaylist(@NonNull Context context, @NonNull Playlist playlist) {
        List<? extends Song> songs = playlist.getSongs(context);
        List<io.github.omblivion.needle.provider.ReferenceKey> keys = new ArrayList<>(songs.size());
        for (Song song : songs) {
            if (!song.data.isEmpty()) keys.add(io.github.omblivion.needle.provider.ReferenceKey.of(song));
        }

        HistoryStore.getInstance(context).addSongKeys(keys);
        super.importPlaylist(context, playlist);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    protected HistoryPlaylist(Parcel in) {
        super(in);
    }

    public static final Creator<HistoryPlaylist> CREATOR = new Creator<HistoryPlaylist>() {
        public HistoryPlaylist createFromParcel(Parcel source) {
            return new HistoryPlaylist(source);
        }

        public HistoryPlaylist[] newArray(int size) {
            return new HistoryPlaylist[size];
        }
    };
}
