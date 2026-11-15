package io.github.omblivion.needle.adapter.artist;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import com.kabouzeid.appthemehelper.util.ColorUtil;
import com.kabouzeid.appthemehelper.util.MaterialValueHelper;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.adapter.base.AbsMultiSelectAdapter;
import io.github.omblivion.needle.adapter.base.MediaEntryViewHolder;
import io.github.omblivion.needle.databinding.ItemGridBinding;
import io.github.omblivion.needle.databinding.ItemListBinding;
import io.github.omblivion.needle.glide.GlideApp;
import io.github.omblivion.needle.glide.NeedleColoredTarget;
import io.github.omblivion.needle.glide.NeedleGlideExtension;
import io.github.omblivion.needle.helper.menu.SongsMenuHelper;
import io.github.omblivion.needle.interfaces.PaletteColorHolder;
import io.github.omblivion.needle.model.Artist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.sort.ArtistSortOrder;
import io.github.omblivion.needle.sort.SortOrder;
import io.github.omblivion.needle.ui.activities.base.AbsThemeActivity;
import io.github.omblivion.needle.util.ImageTheme.ThemeStyleUtil;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.NavigationUtil;
import io.github.omblivion.needle.util.PreferenceUtil;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class ArtistAdapter extends AbsMultiSelectAdapter<ArtistAdapter.ViewHolder, Artist> implements FastScrollRecyclerView.SectionedAdapter {

    protected final AppCompatActivity activity;
    protected ArrayList<Artist> dataSet;

    protected final int itemLayoutRes;

    protected boolean usePalette;

    public ArtistAdapter(@NonNull final AbsThemeActivity activity, ArrayList<Artist> dataSet, @LayoutRes int itemLayoutRes,
                         boolean usePalette, @Nullable final PaletteColorHolder palette)
    {
        super(activity, palette, R.menu.menu_media_selection);
        this.activity = activity;
        this.dataSet = dataSet;
        this.itemLayoutRes = itemLayoutRes;
        this.usePalette = usePalette;
        setHasStableIds(true);
    }

    public void swapDataSet(ArrayList<Artist> dataSet) {
        this.dataSet = dataSet;
        notifyDataSetChanged();
    }

    public ArrayList<Artist> getDataSet() {
        return dataSet;
    }

    public void usePalette(boolean usePalette) {
        this.usePalette = usePalette;
        notifyDataSetChanged();
    }

    public void showFooter(boolean showFooter) {
    }

    @Override
    public long getItemId(int position) {
        return dataSet.get(position).getId();
    }

    @Override
    @NonNull
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        if (itemLayoutRes == R.layout.item_grid) {
            ItemGridBinding binding = ItemGridBinding.inflate(inflater, parent, false);
            return new ViewHolder(binding);
        }
        else if (itemLayoutRes == R.layout.item_list) {
            ItemListBinding binding = ItemListBinding.inflate(inflater, parent, false);
            return new ViewHolder(binding);
        }
        else {
            throw new AssertionError("Unsupported artist layout=" + itemLayoutRes);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final Artist artist = dataSet.get(position);

        holder.itemView.setActivated(isChecked(position));

        if (holder.getAdapterPosition() == getItemCount() - 1) {
            if (holder.shortSeparator != null) {
                holder.shortSeparator.setVisibility(View.GONE);
            }
        } else {
            if (holder.shortSeparator != null) {
                holder.shortSeparator.setVisibility(ThemeStyleUtil.getInstance().getShortSeparatorVisibilityState());
            }
        }

        if (holder.title != null) {
            holder.title.setText(artist.getName());
        }
        if (holder.text != null) {
            holder.text.setText(MusicUtil.getArtistInfoString(activity, artist));
        }

        loadArtistImage(artist, holder);
    }

    void setColors(int color, ViewHolder holder) {
        if (holder.paletteColorContainer != null) {
            holder.paletteColorContainer.setBackgroundColor(color);
            if (holder.title != null) {
                holder.title.setTextColor(MaterialValueHelper.getPrimaryTextColor(activity, ColorUtil.isColorLight(color)));
            }
            if (holder.text != null) {
                holder.text.setTextColor(MaterialValueHelper.getSecondaryTextColor(activity, ColorUtil.isColorLight(color)));
            }
        }
    }

    protected void loadArtistImage(Artist artist, final ViewHolder holder) {
        if (holder.image == null) return;
        GlideApp.with(activity)
                .asBitmapPalette()
                .load(NeedleGlideExtension.getArtistModel(artist))
                .transition(NeedleGlideExtension.getDefaultTransition())
                .artistOptions(artist)
                .into(new NeedleColoredTarget(holder.image) {
                    @Override
                    public void onLoadCleared(Drawable placeholder) {
                        super.onLoadCleared(placeholder);
                        setColors(getDefaultFooterColor(), holder);
                    }

                    @Override
                    public void onColorReady(int color) {
                        if (usePalette)
                            setColors(color, holder);
                        else
                            setColors(getDefaultFooterColor(), holder);
                    }
                });
    }

    @Override
    public int getItemCount() {
        return dataSet.size();
    }

    @Override
    protected Artist getIdentifier(int position) {
        return dataSet.get(position);
    }

    @Override
    protected void onMultipleItemAction(@NonNull final MenuItem menuItem, @NonNull final Map<Integer, Artist> selection) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection.values().iterator()), menuItem.getItemId());
    }

    @NonNull
    private ArrayList<Song> getSongList(@NonNull Iterator<Artist> artists) {
        final ArrayList<Song> songs = new ArrayList<>();
        artists.forEachRemaining((artist) -> songs.addAll(artist.getSongs()));
        return songs;
    }

    @NonNull
    @Override
    public String getSectionName(int position) {
        SortOrder<Artist> sortOrder = ArtistSortOrder.fromPreference(PreferenceUtil.getInstance().getArtistSortOrder());
        return sortOrder.sectionNameBuilder.apply(dataSet.get(position));
    }

    public class ViewHolder extends MediaEntryViewHolder {
        public ViewHolder(@NonNull final ItemListBinding binding) {
            super(binding);

            View itemView = binding.getRoot();
            ThemeStyleUtil.getInstance().setHeightListItem(itemView, activity.getResources().getDisplayMetrics().density);

            setImageTransitionName(activity.getString(R.string.transition_artist_image));
            imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getArtistRadiusImage(activity));
            menu.setVisibility(View.GONE);
        }

        public ViewHolder(@NonNull final ItemGridBinding binding) {
            super(binding);
            setImageTransitionName(activity.getString(R.string.transition_artist_image));
            imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getAlbumRadiusImage(activity));
        }

        @Override
        public void onClick(View v) {
            if (isInQuickSelectMode()) {
                toggleChecked(getAdapterPosition());
            } else {
                Pair<View, String>[] artistPairs = new Pair[]{
                        Pair.create(image,
                                activity.getResources().getString(R.string.transition_artist_image)
                        )};
                NavigationUtil.goToArtist(activity, dataSet.get(getBindingAdapterPosition()).getId(), artistPairs);
            }
        }

        @Override
        public boolean onLongClick(View view) {
            toggleChecked(getAdapterPosition());
            return true;
        }
    }
}
