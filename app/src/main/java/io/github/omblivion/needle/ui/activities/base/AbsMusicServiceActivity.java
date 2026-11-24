package io.github.omblivion.needle.ui.activities.base;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.helper.MusicPlayerRemote;
import io.github.omblivion.needle.interfaces.MusicServiceEventListener;
import io.github.omblivion.needle.service.MusicService;
import io.github.omblivion.needle.provider.UnplayableTrackStore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public abstract class AbsMusicServiceActivity extends AbsBaseActivity implements MusicServiceEventListener {

    private final ArrayList<MusicServiceEventListener> mMusicServiceEventListeners = new ArrayList<>();

    private MusicPlayerRemote.ServiceToken serviceToken;
    private MusicStateReceiver musicStateReceiver;
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serviceToken = MusicPlayerRemote.bindToService(this, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                AbsMusicServiceActivity.this.onServiceConnected();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                AbsMusicServiceActivity.this.onServiceDisconnected();
            }
        });

        setPermissionDeniedMessage(getString(R.string.permission_external_storage_denied));

        final Discography discog = Discography.getInstance();
        discog.addActivity(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        final Discography discog = Discography.getInstance();
        discog.removeActivity(this);

        MusicPlayerRemote.unbindFromService(serviceToken);
        if (receiverRegistered) {
            unregisterReceiver(musicStateReceiver);
            receiverRegistered = false;
        }
    }

    public void addMusicServiceEventListener(final MusicServiceEventListener listener) {
        if (listener != null) {
            mMusicServiceEventListeners.add(listener);
        }
    }

    public void removeMusicServiceEventListener(final MusicServiceEventListener listener) {
        if (listener != null) {
            mMusicServiceEventListeners.remove(listener);
        }
    }

    @Override
    public void onServiceConnected() {
        if (!receiverRegistered) {
            musicStateReceiver = new MusicStateReceiver(this);

            final IntentFilter filter = new IntentFilter();
            filter.addAction(MusicService.PLAY_STATE_CHANGED);
            filter.addAction(MusicService.SHUFFLE_MODE_CHANGED);
            filter.addAction(MusicService.REPEAT_MODE_CHANGED);
            filter.addAction(MusicService.META_CHANGED);
            filter.addAction(MusicService.QUEUE_CHANGED);
            filter.addAction(MusicService.MEDIA_STORE_CHANGED);
            filter.addAction(MusicService.FAVORITE_STATE_CHANGED);
            filter.addAction(MusicService.STATISTICS_CHANGED);
            filter.addAction(UnplayableTrackStore.CHANGED);

            registerReceiver(musicStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

            receiverRegistered = true;
        }

        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onServiceConnected();
            }
        }
    }

    @Override
    public void onServiceDisconnected() {
        if (receiverRegistered) {
            unregisterReceiver(musicStateReceiver);
            receiverRegistered = false;
        }

        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onServiceDisconnected();
            }
        }
    }

    @Override
    public void onPlayingMetaChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onPlayingMetaChanged();
            }
        }
    }

    @Override
    public void onQueueChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onQueueChanged();
            }
        }
    }

    @Override
    public void onPlayStateChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onPlayStateChanged();
            }
        }
    }

    @Override
    public void onMediaStoreChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onMediaStoreChanged();
            }
        }
    }

    @Override
    public void onStatisticsChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onStatisticsChanged();
            }
        }
    }

    @Override
    public void onUnplayableTracksChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) listener.onUnplayableTracksChanged();
        }
    }

    @Override
    public void onRepeatModeChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onRepeatModeChanged();
            }
        }
    }

    @Override
    public void onShuffleModeChanged() {
        for (MusicServiceEventListener listener : mMusicServiceEventListeners) {
            if (listener != null) {
                listener.onShuffleModeChanged();
            }
        }
    }

    private static final class MusicStateReceiver extends BroadcastReceiver {

        private final WeakReference<AbsMusicServiceActivity> reference;

        public MusicStateReceiver(final AbsMusicServiceActivity activity) {
            reference = new WeakReference<>(activity);
        }

        @Override
        public void onReceive(final Context context, @NonNull final Intent intent) {
            final String action = intent.getAction();
            AbsMusicServiceActivity activity = reference.get();
            if (activity != null) {
                switch (action) {
                    case MusicService.FAVORITE_STATE_CHANGED:
                    case MusicService.META_CHANGED:
                        activity.onPlayingMetaChanged();
                        break;
                    case MusicService.QUEUE_CHANGED:
                        activity.onQueueChanged();
                        break;
                    case MusicService.PLAY_STATE_CHANGED:
                        activity.onPlayStateChanged();
                        break;
                    case MusicService.REPEAT_MODE_CHANGED:
                        activity.onRepeatModeChanged();
                        break;
                    case MusicService.SHUFFLE_MODE_CHANGED:
                        activity.onShuffleModeChanged();
                        break;
                    case MusicService.MEDIA_STORE_CHANGED:
                        activity.onMediaStoreChanged();
                        break;
                    case MusicService.STATISTICS_CHANGED:
                        activity.onStatisticsChanged();
                        break;
                    case UnplayableTrackStore.CHANGED:
                        activity.onUnplayableTracksChanged();
                        break;
                }
            }
        }
    }

    @Override
    protected void onHasPermissionsChanged(boolean hasPermissions) {
        super.onHasPermissionsChanged(hasPermissions);
        Intent intent = new Intent(MusicService.MEDIA_STORE_CHANGED);
        intent.putExtra("from_permissions_changed", true); // just in case we need to know this at some point
        sendBroadcast(intent.setPackage(getPackageName()));
    }

    @Nullable
    @Override
    protected String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            return new String[] { Manifest.permission.READ_MEDIA_AUDIO };
        }
        return super.getRequiredPermissions();
    }

    @Nullable
    @Override
    protected String[] getPermissionsToRequest() {
        // Selected artwork is accessed through a system picker. Notification permission
        // is optional: media session notifications are exempt from that permission.
        return new String[] { Manifest.permission.READ_MEDIA_AUDIO };
    }
}
