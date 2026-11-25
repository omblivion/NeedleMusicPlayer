package io.github.omblivion.needle.helper.menu;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.ReferenceKey;
import io.github.omblivion.needle.provider.SongPlayCountStore;
import io.github.omblivion.needle.service.MusicService;

import java.util.concurrent.Executor;

/** Clears one song's play statistics off the UI thread and refreshes consumers. */
public final class ResetPlayCountsHelper {

    private ResetPlayCountsHelper() {
    }

    /**
     * Resets {@code song}'s play statistics. The database work runs on the
     * supplied executor; on completion a {@link MusicService#STATISTICS_CHANGED}
     * broadcast refreshes most-played consumers.
     */
    public static void reset(@NonNull final Context context, @NonNull final Song song,
                             @NonNull final Executor executor) {
        if (song.id == -1) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            SongPlayCountStore.getInstance(appContext).resetSong(ReferenceKey.of(song));
            appContext.sendBroadcast(new Intent(MusicService.STATISTICS_CHANGED).setPackage(appContext.getPackageName()));
        });
    }
}
