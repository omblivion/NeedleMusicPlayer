package io.github.omblivion.needle.adapter.song;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.discog.tagging.MultiValuesTagUtil;
import io.github.omblivion.needle.interfaces.PaletteColorHolder;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.ui.activities.base.AbsThemeActivity;
import io.github.omblivion.needle.util.MusicUtil;

import java.util.ArrayList;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AlbumSongAdapter extends SongAdapter {

    public AlbumSongAdapter(@NonNull final AbsThemeActivity activity, ArrayList<Song> dataSet, boolean usePalette, @Nullable PaletteColorHolder palette) {
        super(activity, dataSet, R.layout.item_list, usePalette, palette);
        this.showAlbumImage = false; // We don't want to load it in this adapter
    }

    @Override
    public void onBindViewHolder(@NonNull SongAdapter.ViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);

        final Song song = dataSet.get(position);

        if (holder.imageText != null) {
            final String trackNumberString = MusicUtil.getTrackNumberInfoString(song);
            holder.imageText.setText(trackNumberString);
        }
    }

    @Override
    @NonNull
    protected String getSongText(@NonNull final Song song) {
        return MusicUtil.buildInfoString(
                MusicUtil.getReadableDurationString(song.duration),
                MultiValuesTagUtil.infoStringAsArtists(song.artistNames));
    }
}
