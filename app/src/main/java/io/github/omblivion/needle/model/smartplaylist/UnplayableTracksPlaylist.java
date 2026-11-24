package io.github.omblivion.needle.model.smartplaylist;

import android.content.Context;
import android.os.Parcel;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.UnplayableTrackStore;

import java.util.ArrayList;

/** Automatic maintenance list; removing a file requires the device deletion action. */
public class UnplayableTracksPlaylist extends AbsSmartPlaylist {
    public UnplayableTracksPlaylist(@NonNull Context context) {
        super(context.getString(R.string.unplayable_files), R.drawable.ic_error_outline_white_24dp);
    }

    private UnplayableTracksPlaylist(Parcel in) {
        super(in);
    }

    @Override
    public boolean isClearable() {
        return false;
    }

    @NonNull
    @Override
    public ArrayList<Song> getSongs(@NonNull Context context) {
        return UnplayableTrackStore.getInstance(context).getSongs();
    }

    public static final Creator<UnplayableTracksPlaylist> CREATOR = new Creator<>() {
        @Override
        public UnplayableTracksPlaylist createFromParcel(Parcel source) {
            return new UnplayableTracksPlaylist(source);
        }

        @Override
        public UnplayableTracksPlaylist[] newArray(int size) {
            return new UnplayableTracksPlaylist[size];
        }
    };
}
