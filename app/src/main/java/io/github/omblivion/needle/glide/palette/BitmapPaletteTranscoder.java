package io.github.omblivion.needle.glide.palette;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import io.github.omblivion.needle.util.NeedleMusicPlayerColorUtil;

public class BitmapPaletteTranscoder implements ResourceTranscoder<Bitmap, BitmapPaletteWrapper> {
    @Override
    public Resource<BitmapPaletteWrapper> transcode(@NonNull Resource<Bitmap> bitmapResource, @NonNull Options options) {
        Bitmap bitmap = bitmapResource.get();
        BitmapPaletteWrapper bitmapPaletteWrapper = new BitmapPaletteWrapper(bitmap, NeedleMusicPlayerColorUtil.generatePalette(bitmap));
        return new BitmapPaletteResource(bitmapPaletteWrapper);
    }
}
