package io.github.omblivion.needle.adapter.song;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.interfaces.PaletteColorHolder;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.ui.activities.base.AbsThemeActivity;

import java.util.List;

/** Standard device-deletion actions, with the affected path visible for cleanup. */
public class UnplayableSongAdapter extends SongAdapter {
    public UnplayableSongAdapter(@NonNull AbsThemeActivity activity,
                                 @NonNull List<? extends Song> songs,
                                 @NonNull PaletteColorHolder palette) {
        super(activity, songs, R.layout.item_list, false, palette, false);
    }

    @NonNull
    @Override
    protected String getSongText(@NonNull Song song) {
        return song.data;
    }
}
