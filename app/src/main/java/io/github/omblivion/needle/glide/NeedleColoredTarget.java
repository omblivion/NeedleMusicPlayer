package io.github.omblivion.needle.glide;

import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.transition.Transition;
import com.kabouzeid.appthemehelper.util.ATHUtil;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.glide.palette.BitmapPaletteTarget;
import io.github.omblivion.needle.glide.palette.BitmapPaletteWrapper;
import io.github.omblivion.needle.util.NeedleMusicPlayerColorUtil;

public abstract class NeedleColoredTarget extends BitmapPaletteTarget {
    public NeedleColoredTarget(ImageView view) {
        super(view);
    }

    @Override
    public void onLoadFailed(@Nullable Drawable errorDrawable) {
        super.onLoadFailed(errorDrawable);
        onColorReady(getDefaultFooterColor());
    }

    @Override
    public void onResourceReady(@NonNull BitmapPaletteWrapper resource, Transition<? super BitmapPaletteWrapper> glideAnimation) {
        super.onResourceReady(resource, glideAnimation);
        onColorReady(NeedleMusicPlayerColorUtil.getColor(resource.getPalette(), getDefaultFooterColor()));
    }

    protected int getDefaultFooterColor() {
        return ATHUtil.resolveColor(getView().getContext(), R.attr.defaultFooterColor);
    }

    protected int getAlbumArtistFooterColor() {
        return ATHUtil.resolveColor(getView().getContext(), R.attr.cardBackgroundColor);
    }

    public abstract void onColorReady(int color);
}
