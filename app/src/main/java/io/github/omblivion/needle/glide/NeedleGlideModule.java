package io.github.omblivion.needle.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;
import io.github.omblivion.needle.glide.artistimage.ArtistImage;
import io.github.omblivion.needle.glide.artistimage.ArtistImageLoader;
import io.github.omblivion.needle.glide.audiocover.FileCover;
import io.github.omblivion.needle.glide.audiocover.FileCoverLoader;
import io.github.omblivion.needle.glide.audiocover.SongCover;
import io.github.omblivion.needle.glide.audiocover.SongCoverLoader;
import io.github.omblivion.needle.glide.palette.BitmapPaletteTranscoder;
import io.github.omblivion.needle.glide.palette.BitmapPaletteWrapper;

import java.io.InputStream;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */

@GlideModule
public class NeedleGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.append(FileCover.class, InputStream.class, new FileCoverLoader.Factory());
        registry.append(SongCover.class, InputStream.class, new SongCoverLoader.Factory());
        registry.append(ArtistImage.class, InputStream.class, new ArtistImageLoader.Factory(context));
        registry.register(Bitmap.class, BitmapPaletteWrapper.class, new BitmapPaletteTranscoder());
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Set to warn to mostly avoid the missing cover art exception dumps
        builder.setLogLevel(Log.WARN);
    }
}
