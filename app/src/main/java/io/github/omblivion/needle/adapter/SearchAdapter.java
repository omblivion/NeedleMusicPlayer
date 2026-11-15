package io.github.omblivion.needle.adapter;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.RecyclerView;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.adapter.base.MediaEntryViewHolder;
import io.github.omblivion.needle.databinding.ItemListBinding;
import io.github.omblivion.needle.databinding.SubHeaderBinding;
import io.github.omblivion.needle.databinding.SubHeaderMaterialBinding;
import io.github.omblivion.needle.glide.GlideApp;
import io.github.omblivion.needle.glide.NeedleGlideExtension;
import io.github.omblivion.needle.helper.MusicPlayerRemote;
import io.github.omblivion.needle.helper.menu.SongMenuHelper;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.model.Artist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.ImageTheme.ThemeStyleUtil;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.NavigationUtil;
import io.github.omblivion.needle.util.PlayingSongDecorationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.ViewHolder> {

    private static final int HEADER = 0;
    private static final int ALBUM = 1;
    private static final int ARTIST = 2;
    private static final int SONG = 3;

    final AppCompatActivity activity;
    List<Object> dataSet;

    public SearchAdapter(@NonNull AppCompatActivity activity, @NonNull List<Object> dataSet) {
        this.activity = activity;
        this.dataSet = dataSet;
    }

    public void swapDataSet(@NonNull List<Object> dataSet) {
        this.dataSet = dataSet;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (dataSet.get(position) instanceof Album) return ALBUM;
        if (dataSet.get(position) instanceof Artist) return ARTIST;
        if (dataSet.get(position) instanceof Song) return SONG;
        return HEADER;
    }

    @Override
    @NonNull
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        if (viewType == HEADER) {
            return ThemeStyleUtil.getInstance().HeaderViewHolder(this, inflater, parent, false);
        }
        ItemListBinding binding = ItemListBinding.inflate(inflater, parent, false);
        return new ViewHolder(binding, viewType);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case ALBUM:
                final Album album = (Album) dataSet.get(position);
                holder.title.setText(album.getTitle());
                holder.text.setText(MusicUtil.getAlbumInfoString(activity, album));
                GlideApp.with(activity)
                        .asDrawable()
                        .load(NeedleGlideExtension.getSongModel(album.safeGetFirstSong()))
                        .transition(NeedleGlideExtension.getDefaultTransition())
                        .songOptions(album.safeGetFirstSong())
                        .into(holder.image);
                break;
            case ARTIST:
                final Artist artist = (Artist) dataSet.get(position);
                holder.title.setText(artist.getName());
                holder.text.setText(MusicUtil.getArtistInfoString(activity, artist));
                GlideApp.with(activity)
                        .asBitmap()
                        .load(NeedleGlideExtension.getArtistModel(artist))
                        .transition(NeedleGlideExtension.getDefaultTransition())
                        .artistOptions(artist)
                        .into(holder.image);
                break;
            case SONG:
                final Song song = (Song) dataSet.get(position);
                holder.title.setText(song.getTitle());
                holder.text.setText(MusicUtil.getSongInfoString(song));
                GlideApp.with(activity)
                        .asBitmap()
                        .load(NeedleGlideExtension.getSongModel(song))
                        .transition(NeedleGlideExtension.getDefaultTransition())
                        .songOptions(song)
                        .into(holder.image);

                PlayingSongDecorationUtil.decorate(holder.title, holder.image, holder.imageText, song, activity, ThemeStyleUtil.getInstance().showSongAlbumArt());
                break;
            default:
                ThemeStyleUtil.getInstance().setHeaderText(holder, activity, dataSet.get(position).toString());
                break;
        }
    }

    @Override
    public int getItemCount() {
        return dataSet.size();
    }

    public class ViewHolder extends MediaEntryViewHolder {
        public ViewHolder(@NonNull SubHeaderBinding binding) {
            super(binding);
            binding.getRoot().setOnLongClickListener(null);
        }

        public ViewHolder(@NonNull SubHeaderMaterialBinding binding) {
            super(binding);
            binding.getRoot().setOnLongClickListener(null);
        }

        public ViewHolder(@NonNull ItemListBinding binding, int itemViewType) {
            super(binding);

            View itemView = binding.getRoot();
            itemView.setOnLongClickListener(null);

            ThemeStyleUtil.getInstance().setHeightListItem(itemView, activity.getResources().getDisplayMetrics().density);
            ThemeStyleUtil.getInstance().setSearchCardItemStyle(itemView, activity);

            if (shortSeparator != null) {
                shortSeparator.setVisibility(View.GONE);
            }

            if (menu != null) {
                if (itemViewType == SONG) {
                    menu.setVisibility(View.VISIBLE);
                    menu.setOnClickListener(new SongMenuHelper.OnClickSongMenu(activity) {
                        @Override
                        public Song getSong() {
                            return (Song) dataSet.get(getBindingAdapterPosition());
                        }

                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            return onSongMenuItemClick(item, getSong().albumId) || super.onMenuItemClick(item);
                        }
                    });
                } else {
                    menu.setVisibility(View.GONE);
                }
            }

            switch (itemViewType) {
                case ALBUM:
                case SONG:
                    setImageTransitionName(activity.getString(R.string.transition_album_art));
                    imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getAlbumRadiusImage(activity));
                    break;
                case ARTIST:
                    setImageTransitionName(activity.getString(R.string.transition_artist_image));
                    imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getArtistRadiusImage(activity));
                    break;
            }
        }

        protected boolean onSongMenuItemClick(MenuItem item, long albumId) {
            if ((image != null) && (image.getVisibility() == View.VISIBLE) && (item.getItemId() == R.id.action_go_to_album)) {
                Pair[] albumPairs = new Pair[]{
                        Pair.create(image, activity.getResources().getString(R.string.transition_album_art))
                };
                NavigationUtil.goToAlbum(activity, albumId, albumPairs);
                return true;
            }
            return false;
        }

        @Override
        public void onClick(View view) {
            Object item = dataSet.get(getBindingAdapterPosition());
            switch (getItemViewType()) {
                case ALBUM:
                    NavigationUtil.goToAlbum(activity,
                            ((Album) item).getId(),
                            Pair.create(image,
                                    activity.getResources().getString(R.string.transition_album_art)
                            ));
                    break;
                case ARTIST:
                    NavigationUtil.goToArtist(activity,
                            ((Artist) item).getId(),
                            Pair.create(image,
                                    activity.getResources().getString(R.string.transition_artist_image)
                            ));
                    break;
                case SONG:
                    ArrayList<Song> playList = new ArrayList<>();
                    playList.add((Song) item);
                    MusicPlayerRemote.enqueueSongsWithConfirmation(activity, playList, 0);
                    break;
            }
        }
    }
}
