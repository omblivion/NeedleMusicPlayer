package io.github.omblivion.needle.adapter.album;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

import com.kabouzeid.appthemehelper.util.ColorUtil;
import com.kabouzeid.appthemehelper.util.MaterialValueHelper;
import io.github.omblivion.needle.databinding.ItemGridCardHorizontalBinding;
import io.github.omblivion.needle.glide.GlideApp;
import io.github.omblivion.needle.glide.NeedleColoredTarget;
import io.github.omblivion.needle.glide.NeedleGlideExtension;
import io.github.omblivion.needle.helper.HorizontalAdapterHelper;
import io.github.omblivion.needle.interfaces.PaletteColorHolder;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.ui.activities.base.AbsThemeActivity;
import io.github.omblivion.needle.util.ImageTheme.ThemeStyleUtil;
import io.github.omblivion.needle.util.MusicUtil;

import java.util.ArrayList;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class HorizontalAlbumAdapter extends AlbumAdapter {

    public HorizontalAlbumAdapter(@NonNull final AbsThemeActivity activity, ArrayList<Album> dataSet, boolean usePalette,
                                  @Nullable PaletteColorHolder palette) {
        super(activity, dataSet, HorizontalAdapterHelper.LAYOUT_RES, true, usePalette, palette);
    }

    @Override
    @NonNull
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        ItemGridCardHorizontalBinding binding = ItemGridCardHorizontalBinding.inflate(inflater, parent, false);

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) binding.getRoot().getLayoutParams();
        HorizontalAdapterHelper.applyMarginToLayoutParams(activity, params, viewType);
        return new ViewHolder(binding);
    }

    protected void updateDetails(int color, ViewHolder holder) {
        CardView card = (CardView) holder.itemView;
        card.setCardBackgroundColor(color);
        if (holder.title != null) {
            holder.title.setTextColor(MaterialValueHelper.getPrimaryTextColor(activity, ColorUtil.isColorLight(color)));
        }
        if (holder.text != null) {
            holder.text.setTextColor(MaterialValueHelper.getSecondaryTextColor(activity, ColorUtil.isColorLight(color)));
        }
    }

    @Override
    protected void loadAlbumCover(Album album, final ViewHolder holder) {
        if (holder.image == null) return;

        if (holder.imageBorderTheme != null) {
            holder.imageBorderTheme.setRadius(ThemeStyleUtil.getInstance().getAlbumRadiusImage(activity));
        }

        GlideApp.with(activity)
                .asBitmapPalette()
                .load(NeedleGlideExtension.getSongModel(album.safeGetFirstSong()))
                .transition(NeedleGlideExtension.getDefaultTransition())
                .songOptions(album.safeGetFirstSong())
                .into(new NeedleColoredTarget(holder.image) {
                    @Override
                    public void onLoadCleared(Drawable placeholder) {
                        super.onLoadCleared(placeholder);
                        updateDetails(getAlbumArtistFooterColor(), holder);
                    }

                    @Override
                    public void onColorReady(int color) {
                        if (usePalette)
                            updateDetails(color, holder);
                        else
                            updateDetails(getAlbumArtistFooterColor(), holder);
                    }
                });
    }

    @NonNull
    @Override
    protected String getAlbumText(@NonNull final Album album) {
        return MusicUtil.getYearString(album.getYear());
    }

    @Override
    public int getItemViewType(int position) {
        return HorizontalAdapterHelper.getItemViewtype(position, getItemCount());
    }
}
