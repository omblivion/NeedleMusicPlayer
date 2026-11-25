package io.github.omblivion.needle.discog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.ui.activities.base.AbsMusicServiceActivity;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.OopsHandler;
import io.github.omblivion.needle.util.SafeToast;

/**
 * @author SC (soncaokim)
 */

class SyncWithMediaStoreAsyncTask extends AsyncTask<Void, SyncWithMediaStoreAsyncTask.Progress, SyncWithMediaStoreAsyncTask.Progress> {
    @NonNull
    private final Discography discography;

    @SuppressLint("StaticFieldLeak") // short lived reference, only while the sync operation is running
    @NonNull
    private final Context context;

    @NonNull
    private final SnackbarUtil snackbar;

    private final boolean resetRequested;

    public static class Progress {
        public int added = 0;
        public int removed = 0;
        public int updated = 0;
        boolean deferred;

        boolean isEmpty() {
            return added == 0 && removed == 0 && updated == 0;
        }

        @NonNull
        String buildInfoString(@NonNull final Context context) {
            if (isEmpty()) return "";

            final Resources resources = context.getResources();
            return MusicUtil.buildInfoString(
                    (added > 0) ? resources.getString(R.string.scanning_x_songs_added, added) : "",
                    (updated > 0) ? resources.getString(R.string.scanning_x_songs_updated, updated) : "",
                    (removed > 0) ? resources.getString(R.string.scanning_x_songs_removed, removed) : ""
            );
        }
    }

    SyncWithMediaStoreAsyncTask(@NonNull final AbsMusicServiceActivity containerActivity, @NonNull final Discography discog, final boolean reset) {
        discography = discog;
        context = containerActivity;
        snackbar = new SnackbarUtil(containerActivity.getSnackBarContainer());
        resetRequested = reset;
    }

    @Override
    protected Progress doInBackground(final Void...params) {
        try {
            return discography.syncWithMediaStore(this::publishProgress, resetRequested);
        } catch (RuntimeException error) {
            OopsHandler.collectStackTrace(error);
            return null;
        }
    }

    @Override
    protected void onPreExecute() {
        discography.setCacheState(resetRequested ? MemCache.ConsistencyState.RESETTING : MemCache.ConsistencyState.REFRESHING);
        startTimeMs = System.currentTimeMillis();
    }

    @Override
    protected void onProgressUpdate(final Progress... values) {
        final Progress last = values[values.length - 1];
        if (!last.isEmpty() && isUIFeedbackNeeded()) {
            snackbar.showProgress(last.buildInfoString(context));
        }
    }

    @Override
    protected void onPostExecute(final Progress value) {
        onTermination(value);
    }
    @Override
    protected void onCancelled(final Progress value) {
        onTermination(value);
    }

    private void onTermination(@Nullable final Progress value) {
        discography.setCacheState(MemCache.ConsistencyState.OK);
        if (value == null && !isCancelled()) {
            SafeToast.show(context, R.string.library_scan_failed);
        }
        if (isUIFeedbackNeeded()) {
            if (value == null || value.isEmpty()) {
                snackbar.dismiss();
            } else {
                snackbar.showProgress(value.buildInfoString(context));
            }
        }
    }

    private long startTimeMs = 0L;
    private boolean isUIFeedbackNeeded() {
        final long UI_VISIBLE_THRESHOLD_MS = 500L;
        final long now = System.currentTimeMillis();

        return (now - startTimeMs > UI_VISIBLE_THRESHOLD_MS);
    }
}
