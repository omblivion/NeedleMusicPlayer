package io.github.omblivion.needle.service;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import io.github.omblivion.needle.util.OopsHandler;

class QueueSaveHandler extends Handler {
    static final int SAVE_QUEUES = 0;
    static final int RESTORE_QUEUES = 1;

    @NonNull
    private final WeakReference<MusicService> mService;

    public QueueSaveHandler(final MusicService service, @NonNull final Looper looper) {
        super(looper);
        mService = new WeakReference<>(service);
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        final MusicService service = mService.get();
        if (service == null) return;
        try {
            switch (msg.what) {
                case SAVE_QUEUES -> service.saveQueuesImpl();
                case RESTORE_QUEUES -> service.restoreQueuesAndPosition();
            }
        } catch (SQLiteException failure) {
            // The store has already rolled back. Keep playback and future saves alive.
            Log.e("QueueSaveHandler", "Could not persist or restore the playback queue", failure);
            OopsHandler.collectStackTrace(failure);
        }
    }
}
