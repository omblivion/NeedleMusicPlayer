package io.github.omblivion.needle.model;

import android.os.Parcel;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */

public abstract class AbsCustomPlaylist extends Playlist {
    public AbsCustomPlaylist(long id, String name) {
        super(id, name);
    }

    public AbsCustomPlaylist() {
    }

    public AbsCustomPlaylist(Parcel in) {
        super(in);
    }
}
