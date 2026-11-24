package io.github.omblivion.needle.service;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.util.Predicate;
import androidx.media.MediaBrowserServiceCompat;

import io.github.omblivion.needle.R;
import io.github.omblivion.needle.App;
import io.github.omblivion.needle.appwidgets.AppWidgetBig;
import io.github.omblivion.needle.appwidgets.AppWidgetCard;
import io.github.omblivion.needle.appwidgets.AppWidgetClassic;
import io.github.omblivion.needle.appwidgets.AppWidgetSmall;
import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.discog.tagging.MultiValuesTagUtil;
import io.github.omblivion.needle.glide.audiocover.SongCover;
import io.github.omblivion.needle.glide.audiocover.SongCoverFetcher;
import io.github.omblivion.needle.helper.PendingIntentCompat;
import io.github.omblivion.needle.helper.WeakMethodReference;
import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.misc.queue.StaticPlayingQueue;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.model.Playlist;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.HistoryStore;
import io.github.omblivion.needle.provider.MusicPlaybackQueueStore;
import io.github.omblivion.needle.provider.SongPlayCountStore;
import io.github.omblivion.needle.provider.UnplayableTrackStore;
import io.github.omblivion.needle.service.notification.CrashNotification;
import io.github.omblivion.needle.service.notification.IdleNotification;
import io.github.omblivion.needle.service.notification.PlayingNotification;
import io.github.omblivion.needle.service.notification.PlayingNotificationImplApi19;
import io.github.omblivion.needle.service.notification.PlayingNotificationImplApi24;
import io.github.omblivion.needle.service.playback.Playback;
import io.github.omblivion.needle.util.MusicUtil;
import io.github.omblivion.needle.util.OopsHandler;
import io.github.omblivion.needle.util.PackageValidator;
import io.github.omblivion.needle.util.PlaylistsUtil;
import io.github.omblivion.needle.util.PrefKey;
import io.github.omblivion.needle.util.PreferenceUtil;
import io.github.omblivion.needle.util.SafeToast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * @author Karim Abou Zeid (kabouzeid), Andrew Neal
 */
public class MusicService extends MediaBrowserServiceCompat implements SharedPreferences.OnSharedPreferenceChangeListener, Playback.PlaybackCallbacks {

    public static final String TAG = MusicService.class.getSimpleName();

    static final String NEEDLE_MUSIC_PLAYER_PACKAGE_NAME = io.github.omblivion.needle.BuildConfig.APPLICATION_ID;
    private static final String MUSIC_PACKAGE_NAME = "com.android.music";

    public static final String ACTION_TOGGLE_PAUSE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".togglepause";
    static final String ACTION_PLAY = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".play";
    public static final String ACTION_PLAY_PLAYLIST = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".play.playlist";
    static final String ACTION_PAUSE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".pause";
    static final String ACTION_STOP = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".stop";
    public static final String ACTION_SKIP = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".skip";
    public static final String ACTION_REWIND = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".rewind";
    public static final String ACTION_QUIT = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".quitservice";
    public static final String ACTION_PENDING_QUIT = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".pendingquitservice";
    public static final String INTENT_EXTRA_PLAYLIST = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + "intentextra.playlist";
    public static final String INTENT_EXTRA_SHUFFLE_MODE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".intentextra.shufflemode";

    public static final String APP_WIDGET_UPDATE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".appwidgetupdate";
    public static final String EXTRA_APP_WIDGET_NAME = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + "app_widget_name";

    // Do not change these three strings as it will break support with other apps (e.g. last.fm scrobbling)
    public static final String META_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".metachanged";
    public static final String QUEUE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".queuechanged";
    public static final String PLAY_STATE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".playstatechanged";

    public static final String FAVORITE_STATE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".favoritestatechanged";

    // Sent after a play count is recorded, so most-played consumers refresh
    // without a synthetic playback metadata change.
    public static final String STATISTICS_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".statisticschanged";

    public static final String REPEAT_MODE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".repeatmodechanged";
    public static final String SHUFFLE_MODE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".shufflemodechanged";
    public static final String MEDIA_STORE_CHANGED = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".mediastorechanged";

    static final String CYCLE_REPEAT = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".cyclerepeat";
    static final String TOGGLE_SHUFFLE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".toggleshuffle";
    public static final String TOGGLE_FAVORITE = NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".togglefavorite";

    private static final String SAVED_POSITION = PrefKey.nonExportableKey("POSITION");
    private static final String SAVED_POSITION_IN_TRACK = PrefKey.nonExportableKey("POSITION_IN_TRACK");
    private static final String SAVED_SHUFFLE_MODE = PrefKey.exportableKey("SHUFFLE_MODE");
    private static final String SAVED_REPEAT_MODE = PrefKey.exportableKey("REPEAT_MODE");

    static final int RELEASE_WAKELOCK = 0;
    static final int TRACK_ENDED = 1;
    static final int TRACK_WENT_TO_NEXT = 2;
    static final int PLAY_SONG = 3;
    static final int PREPARE_NEXT = 4;
    static final int SET_POSITION = 5;
    static final int FOCUS_CHANGE = 6;
    static final int DUCK = 7;
    static final int UNDUCK = 8;
    static final int TRACK_FAILED = 9;
    static final int CHECK_UNPLAYABLE_RECOVERY = 10;

    public static final int RANDOM_START_POSITION_ON_SHUFFLE = StaticPlayingQueue.INVALID_POSITION;
    public static final int SHUFFLE_MODE_NONE = StaticPlayingQueue.SHUFFLE_MODE_NONE;
    public static final int SHUFFLE_MODE_SHUFFLE = StaticPlayingQueue.SHUFFLE_MODE_SHUFFLE;

    public static final int REPEAT_MODE_NONE = StaticPlayingQueue.REPEAT_MODE_NONE;
    public static final int REPEAT_MODE_ALL = StaticPlayingQueue.REPEAT_MODE_ALL;
    public static final int REPEAT_MODE_THIS = StaticPlayingQueue.REPEAT_MODE_THIS;

    private static final int SKIP_THRESHOLD_MS = 5000;

    /** Maximum number of consecutive automatic skips before playback stops. */
    public static final int MAX_CONSECUTIVE_SKIPS = 5;

    /** Playback progress after which a track counts as really played. */
    public static final int SKIP_RESET_PROGRESS_MS = 10000;

    @RequiresApi(Build.VERSION_CODES.O)
    static AudioAttributes PLAYBACK_ATTRIBUTE;

    private final IBinder musicBind = new MusicBinder();

    public boolean pendingQuit = false;

    final AppWidgetBig appWidgetBig = AppWidgetBig.getInstance();
    final AppWidgetClassic appWidgetClassic = AppWidgetClassic.getInstance();
    final AppWidgetSmall appWidgetSmall = AppWidgetSmall.getInstance();
    final AppWidgetCard appWidgetCard = AppWidgetCard.getInstance();

    private StaticPlayingQueue playingQueue = new StaticPlayingQueue();

    private boolean queuesRestored;
    private boolean pausedByTransientLossOfFocus;

    private PlayingNotification playingNotification;
    private IdleNotification idleNotification;
    private CrashNotification crashNotification;

    private AudioManager audioManager;
    MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;

    @Nullable private Playback playback;
    PlaybackHandler playbackHandler;
    HandlerThread playbackHandlerThread;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(final int focusChange) {
            synchronized (MusicService.this) {
                if (playbackHandlerThread.isAlive()) {
                    playbackHandler.obtainMessage(FOCUS_CHANGE, focusChange, 0).sendToTarget();
                }
            }
        }
    };
    @RequiresApi(Build.VERSION_CODES.O)
    private AudioFocusRequest focusRequest;

    private QueueSaveHandler queueSaveHandler;
    private HandlerThread queueSaveHandlerThread;

    private final SongPlayCountHelper songPlayCountHelper = new SongPlayCountHelper();
    private ThrottledSeekHandler throttledSeekHandler;
    private boolean becomingNoisyReceiverRegistered;
    private final IntentFilter becomingNoisyReceiverIntentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            if (intent.getAction().equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                pause();
            }
        }
    };
    private ContentObserver mediaStoreObserver;
    private boolean notHandledMetaChangedForCurrentTrack;

    /** Tracks that failed to play in this session; never persisted. */
    private final UnplayableTrackRegistry unplayableTracks = new UnplayableTrackRegistry();
    private Song recoverySong = Song.EMPTY_SONG;
    private long recoveryLastSampleAt;
    private int recoveryLastPosition;
    private long recoveryProgressMillis;

    /** Consecutive automatic skips; reset once a track really plays for a while. */
    private int consecutiveSkipCount = 0;

    /**
     * Test seam: a plain {@code MusicService} instance has no base context, so
     * anything touching resources would fail. Attach the application context.
     */
    void attachBaseContextForTest() {
        if (getBaseContext() == null) {
            attachBaseContext(App.getStaticContext());
        }
    }

    private final WeakMethodReference<MusicService> onDiscographyChanged = new WeakMethodReference<>(this, MusicService::onDiscographyChanged);

    private Handler uiThreadHandler;

    private PackageValidator mPackageValidator;
    private BrowsableMusicProvider mBrowsableMusicProvider;

    private static String getTrackUri(@NonNull Song song) {
        return song.contentUri.isEmpty() ? MusicUtil.getSongFileUri(song.id).toString()
                : song.contentUri;
    }

    private Song openedSong;
    private io.github.omblivion.needle.model.SongSource openedSource;
    private long nextSourceToken;
    private final java.util.Map<Long, PreparedSource> preparedSources = new java.util.HashMap<>();

    static final class PreparedSource {
        final Song song;
        final int position;
        PreparedSource(Song song, int position) {
            this.song = new Song(song);
            this.position = position;
        }
    }

    /** @return true when the track at {@code position} is known to be unplayable. */
    boolean isTrackUnplayable(int position) {
        return unplayableTracks.isUnplayable(getTrackUri(getSongAt(position)));
    }

    /** @return true when {@code uri} is known to be unplayable. */
    boolean isTrackUriUnplayable(String uri) {
        return unplayableTracks.isUnplayable(uri);
    }

    int getConsecutiveSkipCount() {
        synchronized (this) {
            return consecutiveSkipCount;
        }
    }

    void setRepeatModeForTest(int mode) {
        synchronized (this) {
            playingQueue.setRepeatMode(mode);
        }
    }

    void setPositionForTest(int position) {
        synchronized (this) {
            playingQueue.setCurrentPosition(position);
        }
    }

    void markUriUnplayableForTest(String uri) {
        unplayableTracks.markUnplayable(uri);
    }

    String trackUriAtForTest(int position) {
        return getTrackUri(getSongAt(position));
    }

    /**
     * Test seam: builds a queue whose tracks carry {@code trackUris} as their
     * data, and makes it the current queue with {@code currentPosition}.
     */
    void openQueueForTest(String[] trackUris, int currentPosition) {
        attachBaseContextForTest();
        final ArrayList<Song> songs = new ArrayList<>(trackUris.length);
        for (int i = 0; i < trackUris.length; i++) {
            songs.add(new Song(i + 1, trackUris[i], i + 1, 0, 0L, trackUris[i], 0L, 0L,
                    -1L, "", new ArrayList<>(0)));
        }
        synchronized (this) {
            playingQueue = new StaticPlayingQueue();
            playingQueue.openQueue(songs, currentPosition, SHUFFLE_MODE_NONE);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        wakeLock.setReferenceCounted(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PLAYBACK_ATTRIBUTE = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(PLAYBACK_ATTRIBUTE)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build();
        }
        synchronized (this) {
            playbackHandlerThread = new HandlerThread("PlaybackHandler");
            playbackHandlerThread.start();
            playbackHandler = new PlaybackHandler(this, playbackHandlerThread.getLooper());

            playback = new MultiPlayer(this);
            playback.setCallbacks(this);
        }

        setupMediaSession();

        // queue saving needs to run on a separate thread so that it doesn't block the playback handler events
        queueSaveHandlerThread = new HandlerThread("QueueSaveHandler", Process.THREAD_PRIORITY_BACKGROUND);
        queueSaveHandlerThread.start();
        queueSaveHandler = new QueueSaveHandler(this, queueSaveHandlerThread.getLooper());

        uiThreadHandler = new Handler();

        registerReceiver(widgetIntentReceiver, new IntentFilter(APP_WIDGET_UPDATE), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(updateFavoriteReceiver, new IntentFilter(FAVORITE_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED);

        initNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android O+ requires a foreground service to post a notification asap
            updateNotification();
        }
        mediaStoreObserver = new MediaStoreObserver(this, playbackHandler);
        throttledSeekHandler = new ThrottledSeekHandler(this, playbackHandler);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI, true, mediaStoreObserver);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver);

        PreferenceUtil.getInstance().registerOnSharedPreferenceChangedListener(this);

        restoreState();

        mPackageValidator = new PackageValidator(this, R.xml.allowed_media_browser_callers);
        mBrowsableMusicProvider = new BrowsableMusicProvider(this);

        sendBroadcast(new Intent(NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".MUSIC_SERVICE_CREATED").setPackage(getPackageName()));

        Discography.getInstance().addChangedListener(onDiscographyChanged);
        mediaStoreObserver.onChange(true);
    }

    private AudioManager getAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }

    private void setupMediaSession() {
        ComponentName mediaButtonReceiverComponentName = new ComponentName(this, MediaButtonIntentReceiver.class);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mediaButtonReceiverComponentName);

        PendingIntent mediaButtonReceiverPendingIntent = PendingIntentCompat.getBroadcast(this, 0, mediaButtonIntent, 0);

        MediaSessionCallback mMediaSessionCallback = new MediaSessionCallback(this);
        mediaSession = new MediaSessionCompat(this, "NeedleMusicPlayer", mediaButtonReceiverComponentName, mediaButtonReceiverPendingIntent);
        mediaSession.setCallback(mMediaSessionCallback);
        mediaSession.setActive(true);
        mediaSession.setMediaButtonReceiver(mediaButtonReceiverPendingIntent);
        setSessionToken(mediaSession.getSessionToken());
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getAction() != null) {
                // Just in case the async queue restore has not been excuted yet
                synchronized (this) {
                    if (!queuesRestored) {
                        restoreQueuesAndPosition();
                        queuesRestored = true;
                    }
                }

                String action = intent.getAction();
                switch (action) {
                    case ACTION_TOGGLE_PAUSE:
                        if (isPlaying()) {
                            pause();
                        } else {
                            play();
                        }
                        break;
                    case ACTION_PAUSE:
                        pause();
                        break;
                    case ACTION_PLAY:
                        play();
                        break;
                    case ACTION_PLAY_PLAYLIST:
                        Playlist playlist = intent.getParcelableExtra(INTENT_EXTRA_PLAYLIST);
                        if (playlist != null) {
                            List<? extends Song> playlistSongs = playlist.getSongs(this);
                            if (!playlistSongs.isEmpty()) {
                                synchronized (this) {
                                    int shuffleMode = intent.getIntExtra(INTENT_EXTRA_SHUFFLE_MODE, playingQueue.getShuffleMode());
                                    openQueue(playlistSongs, RANDOM_START_POSITION_ON_SHUFFLE, true, shuffleMode);
                                }
                            } else {
                                SafeToast.show(this, R.string.playlist_is_empty);
                            }
                        } else {
                            SafeToast.show(this, R.string.playlist_is_empty);
                        }
                        break;
                    case ACTION_REWIND:
                        back(true);
                        break;
                    case ACTION_SKIP:
                        playNextSong(true);
                        break;
                    case TOGGLE_FAVORITE:
                        MusicUtil.toggleFavorite(this, getCurrentSong());
                        break;
                    case ACTION_STOP:
                    case ACTION_QUIT:
                        pendingQuit = false;
                        quit();
                        break;
                    case ACTION_PENDING_QUIT:
                        pendingQuit = true;
                        if (PreferenceUtil.getInstance().gaplessPlayback()) {
                            synchronized (this) {
                                playback.setNextDataSource(null);
                            }
                        }
                        break;
                }
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Discography.getInstance().removeChangedListener(onDiscographyChanged);

        unregisterReceiver(widgetIntentReceiver);
        unregisterReceiver(updateFavoriteReceiver);

        synchronized (this) {
            if (becomingNoisyReceiverRegistered) {
                unregisterReceiver(becomingNoisyReceiver);
                becomingNoisyReceiverRegistered = false;
            }
        }
        mediaSession.setActive(false);
        quit();
        releaseResources();
        getContentResolver().unregisterContentObserver(mediaStoreObserver);
        PreferenceUtil.getInstance().unregisterOnSharedPreferenceChangedListener(this);
        wakeLock.release();

        sendBroadcast(new Intent(NEEDLE_MUSIC_PLAYER_PACKAGE_NAME + ".MUSIC_SERVICE_DESTROYED").setPackage(getPackageName()));
    }

    @Override
    public IBinder onBind(Intent intent) {
        // For Android auto, need to call super, or onGetRoot won't be called.
        if (intent != null && "android.media.browse.MediaBrowserService".equals(intent.getAction())) {
            return super.onBind(intent);
        }

        return musicBind;
    }

    void saveQueuesImpl() {
        synchronized (this) {
            io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
                Discography discography = Discography.getInstance();
                playingQueue.reconcileSources(discography.getAllSongsById(),
                        discography.getConfirmedDeletedIds(), false);
                MusicPlaybackQueueStore.getInstance(this).saveQueues(
                        new ArrayList<>(playingQueue.getPlayingQueue()),
                        new ArrayList<>(playingQueue.getOriginalPlayingQueue()));
                return null;
            });
        }
    }

    private void savePosition() {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(SAVED_POSITION, getPosition()).apply();
    }

    void savePositionInTrack() {
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(SAVED_POSITION_IN_TRACK, getSongProgressMillis()).apply();
    }

    private void saveState() {
        saveQueues();
        savePosition();
        savePositionInTrack();
    }

    private void saveQueues() {
        queueSaveHandler.removeMessages(QueueSaveHandler.SAVE_QUEUES);
        queueSaveHandler.sendEmptyMessage(QueueSaveHandler.SAVE_QUEUES);
    }

    private void restoreState() {
        final int shuffleMode = PreferenceManager.getDefaultSharedPreferences(this).getInt(SAVED_SHUFFLE_MODE, 0);
        final int repeateMode = PreferenceManager.getDefaultSharedPreferences(this).getInt(SAVED_REPEAT_MODE, 0);

        synchronized (this) {
            playingQueue.restoreMode(shuffleMode, repeateMode);

            if (queueSaveHandlerThread.isAlive()) {
                queueSaveHandler.removeMessages(QueueSaveHandler.RESTORE_QUEUES);
                queueSaveHandler.sendEmptyMessage(QueueSaveHandler.RESTORE_QUEUES);
            }
        }

        handleAndSendChangeInternal(SHUFFLE_MODE_CHANGED);
        handleAndSendChangeInternal(REPEAT_MODE_CHANGED);
    }

    void restoreQueuesAndPosition() {
        synchronized (this) {
            try {
                // A cold start must resolve saved entries against the committed
                // catalog, never against an empty cache.
                Discography.getInstance().ensureLoaded();
                // The current playback state
                final Song savedSong = getCurrentSong();

                // The saved state
                final MusicPlaybackQueueStore queueStore = MusicPlaybackQueueStore.getInstance(this);
                final ArrayList<IndexedSong> restoredQueue = queueStore.getSavedPlayingQueue();
                final ArrayList<IndexedSong> restoredOriginalQueue = queueStore.getSavedOriginalPlayingQueue();
                final int restoredPosition = PreferenceManager.getDefaultSharedPreferences(this)
                        .getInt(SAVED_POSITION, StaticPlayingQueue.INVALID_POSITION);
                final int restoredPositionInTrack = PreferenceManager.getDefaultSharedPreferences(this)
                        .getInt(SAVED_POSITION_IN_TRACK, -1);

                StaticPlayingQueue restored = new StaticPlayingQueue(
                        restoredQueue,
                        restoredOriginalQueue,
                        restoredPosition,
                        playingQueue.getShuffleMode(),
                        playingQueue.getRepeatMode()
                );
                restored.dropUnresolvedEntries();
                playingQueue = restored;
                queuesRestored = true;

                // Before altering the player state, check that it is really necessary
                // ie. we are changing song in between
                // This prevents changing the player state, as it will stop the playback
                final Song currentSong = getSongAt(getPosition());
                if (!io.github.omblivion.needle.model.SongSource.from(currentSong)
                        .sameTrack(io.github.omblivion.needle.model.SongSource.from(savedSong))) {
                    if (openCurrent() && (restoredPositionInTrack > 0)) {
                        seek(restoredPositionInTrack);
                    }
                    notHandledMetaChangedForCurrentTrack = true;
                    sendChangeInternal(META_CHANGED);
                } // else just leave the playback with the current song

                prepareNext();
                sendChangeInternal(QUEUE_CHANGED);
            } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException queueCopiesOutOfSync) {
                // fallback, when the copies of the restored queues are out of sync or the queues are corrupted
                OopsHandler.collectStackTrace(queueCopiesOutOfSync);
                SafeToast.show(this, R.string.failed_restore_playing_queue);

                final int shuffleMode = playingQueue.getShuffleMode();
                playingQueue = new StaticPlayingQueue();
                playingQueue.setShuffle(shuffleMode);
            }
        }
    }

    public void quit() {
        pause();

        playingNotification.stop();
        idleNotification.stop();
        crashNotification.stop();
        stopForeground(true);

        closeAudioEffectSession();
        getAudioManager().abandonAudioFocusRequest(focusRequest);
        stopSelf();
    }

    private void releaseResources() {
        queueSaveHandler.removeCallbacksAndMessages(null);
        queueSaveHandlerThread.quitSafely();

        synchronized (this) {
            playbackHandler.removeCallbacksAndMessages(null);
            playbackHandlerThread.quitSafely();

            playback.release();
            playback = null;
        }

        mediaSession.release();
    }

    public boolean isPlaying() {
        synchronized (this) {
            return (playback != null) && playback.isPlaying();
        }
    }

    public boolean isPlaying(@NonNull Song song) {
        synchronized (this) {
            if (!isPlaying()) {
                return false;
            }

            return getCurrentSong().isQuickEqual(song);
        }
    }

    public int getPosition() {
        synchronized (this) {
            return playingQueue.getCurrentPosition();
        }
    }

    public void playNextSong(boolean skippedLast) {
        synchronized (this) {
            playSongAt(playingQueue.getNextPosition(skippedLast), skippedLast);
        }
    }

    boolean openTrackAndPrepareNextAt(int position) {
        synchronized (this) {
            // Measure the outgoing track before switching: if it was heard for
            // long enough, the run of skipped tracks is over.
            resetConsecutiveSkipsIfPlayed();
            playingQueue.setCurrentPosition(position);
            boolean prepared = openCurrent();
            if (prepared) prepareNextImpl();
            notifyChange(META_CHANGED);
            notHandledMetaChangedForCurrentTrack = false;
            return prepared;
        }
    }

    private boolean openCurrent() {
        synchronized (this) {
            try {
                Song requested = getSongAt(getPosition());
                PlaybackSourceResolver.Resolution source = PlaybackSourceResolver.resolve(this, requested);
                if (source.status != PlaybackSourceResolver.Status.VERIFIED || playback == null) {
                    return false;
                }
                if (playback.setDataSource(source.uri)) {
                    openedSong = new Song(source.song);
                    openedSource = io.github.omblivion.needle.model.SongSource.from(source.song);
                    return true;
                }
                openedSong = null;
                openedSource = null;
                return false;
            } catch (Exception e) {
                OopsHandler.collectStackTrace(e);
                return false;
            }
        }
    }

    private void prepareNext() {
        synchronized (this) {
            if (playbackHandlerThread.isAlive()) {
                playbackHandler.removeMessages(PREPARE_NEXT);
                playbackHandler.obtainMessage(PREPARE_NEXT).sendToTarget();
            }
        }
    }

    void prepareNextImpl() {
        synchronized (this) {
            try {
                int nextPosition = playingQueue.getNextPosition(false);
                if (getRepeatMode() == REPEAT_MODE_NONE && playingQueue.isLastTrack()) {
                    setNextDataSource(null, nextPosition);
                } else {
                    // A track already known to be unplayable must not be
                    // preloaded; walk forward to the next playable one so the
                    // gapless transition stays silent.
                    int candidate = nextPosition;
                    while (isTrackUnplayable(candidate)) {
                        final int advanced = nextPlayableCandidate(candidate);
                        if (advanced == candidate) {
                            // No further playable track: keep the original
                            // choice rather than spinning.
                            break;
                        }
                        candidate = advanced;
                        if (candidate == nextPosition) {
                            break;
                        }
                    }
                    nextPosition = candidate;
                    setNextDataSource(getTrackUri(getSongAt(nextPosition)), nextPosition);
                }
                playingQueue.setNextPosition(nextPosition);
            } catch (Exception e) {
                OopsHandler.collectStackTrace(e);
            }
        }
    }

    /**
     * Hands the next track to the player for gapless playback. Overridable so
     * tests can observe the preload without a real media player.
     */
    void setNextDataSource(String uri, int position) {
        // Only the current preload is ever needed; drop superseded tokens so the
        // bookkeeping cannot grow without bound.
        preparedSources.clear();
        if (uri == null) {
            playback.setNextDataSource(null);
            return;
        }
        PlaybackSourceResolver.Resolution source = PlaybackSourceResolver.resolve(this, getSongAt(position));
        if (source.status == PlaybackSourceResolver.Status.VERIFIED) {
            long token = ++nextSourceToken;
            preparedSources.put(token, new PreparedSource(source.song, position));
            playback.setNextDataSource(source.uri, token);
        } else {
            playback.setNextDataSource(null);
        }
    }

    /**
     * Returns the position after {@code from}, honoring repeat mode so the
     * preload never walks past the end of a non-repeating queue.
     */
    private int nextPlayableCandidate(int from) {
        final int size = playingQueue.size();
        if (size <= 0) {
            return from;
        }
        if (from + 1 < size) {
            return from + 1;
        }
        return getRepeatMode() == REPEAT_MODE_NONE ? from : 0;
    }

    private void closeAudioEffectSession() {
        int sessionId = 0;
        synchronized (this) {
            if (playback != null) {
                sessionId = playback.getAudioSessionId();
            }
        }
        if (sessionId != 0) {
            final Intent audioEffectsIntent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
            audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(audioEffectsIntent);
        }
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return (getAudioManager().requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } else {
            return (getAudioManager().requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PreferenceUtil.getInstance().classicNotification()) {
            playingNotification = new PlayingNotificationImplApi24();
        } else {
            playingNotification = new PlayingNotificationImplApi19();
        }
        playingNotification.init(this,
                PlayingNotification.NOTIFICATION_CHANNEL_ID,
                R.string.playing_notification_name,
                R.string.playing_notification_description);

        idleNotification = new IdleNotification();
        idleNotification.init(this,
                IdleNotification.NOTIFICATION_CHANNEL_ID,
                R.string.idle_notification_name,
                R.string.idle_notification_description);

        crashNotification = new CrashNotification();
        crashNotification.init(this,
                CrashNotification.NOTIFICATION_CHANNEL_ID,
                R.string.app_crashed,
                R.string.report_a_crash_invitation);
    }

    private void updateNotification() {
        if (!getPlayingQueue().isEmpty()) {
            idleNotification.stop();
            playingNotification.update();
        } else {
            playingNotification.stop();
            idleNotification.update();
        }
    }

    private void updateCrashNotification() {
        final List<String> crashReports = PreferenceUtil.getInstance().getOopsHandlerReports();
        if (crashReports != null && !crashReports.isEmpty()) {
            crashNotification.update();
        } else {
            crashNotification.stop();
        }
    }

    private final BroadcastReceiver updateFavoriteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            updateNotification();
        }
    };

    void updateMediaSessionPlaybackState() {
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        getSongProgressMillis(), 1);

        setCustomAction(stateBuilder);

        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void setCustomAction(PlaybackStateCompat.Builder stateBuilder) {
        int repeatIcon;
        int shuffleIcon;
        int favoriteIcon;

        synchronized(this) {
            final int repeatMode = getRepeatMode();
            if (repeatMode == REPEAT_MODE_THIS) {
                repeatIcon = R.drawable.ic_repeat_one_white_circle_48dp;
            } else if (repeatMode == REPEAT_MODE_ALL) {
                repeatIcon = R.drawable.ic_repeat_white_circle_48dp;
            } else {
                repeatIcon = R.drawable.ic_repeat_white_nocircle_48dp;  // REPEAT_MODE_NONE
            }

            shuffleIcon = playingQueue.getShuffleMode() == SHUFFLE_MODE_NONE ? R.drawable.ic_shuffle_white_nocircle_48dp : R.drawable.ic_shuffle_white_circle_48dp;
            favoriteIcon = MusicUtil.isFavorite(this, getCurrentSong()) ? R.drawable.ic_favorite_white_circle_48dp : R.drawable.ic_favorite_border_white_nocircle_48dp;
        }

        stateBuilder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                CYCLE_REPEAT, getString(R.string.action_cycle_repeat), repeatIcon)
                .build());
        stateBuilder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                TOGGLE_SHUFFLE, getString(R.string.action_toggle_shuffle), shuffleIcon)
                .build());
        stateBuilder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                TOGGLE_FAVORITE, getString(R.string.action_toggle_favorite), favoriteIcon)
                .build());
    }

    private void updateMediaSessionMetaData() {
        final Song song = getCurrentSong();

        if (song.id == Song.EMPTY_SONG.id) {
            mediaSession.setMetadata(null);
            return;
        }

        final MediaMetadataCompat.Builder metaData = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, MultiValuesTagUtil.infoStringAsArtists(song.artistNames))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, MultiValuesTagUtil.infoStringAsArtists(song.albumArtistNames))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, Album.getTitle(song.albumName))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, getPosition() + 1)
                .putLong(MediaMetadataCompat.METADATA_KEY_YEAR, song.year);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            synchronized (this) {
                metaData.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, playingQueue.size());
            }
        }

        // Note: For Android Auto and for Android 13, it is necessary to provide METADATA_KEY_ALBUM_ART
        //       or similar to the MediaSession to have a hi-res cover image displayed,
        //       respectively on the Auto's now playing screen and Android 13's now playing notification/lockscreen
        final SongCoverFetcher fetcher = new SongCoverFetcher(new SongCover(song));
        final InputStream data = fetcher.loadData();
        if (data != null) {
            final Bitmap bitmap = decodeCoverArtSafely(data);
            metaData.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
        }

        mediaSession.setMetadata(metaData.build());
   }

    /**
     * Decodes cover art without letting a corrupt or unreadable image take the
     * service down. A failed decode degrades to no artwork.
     */
    static Bitmap decodeCoverArtSafely(InputStream data) {
        try {
            return BitmapFactory.decodeStream(data);
        } catch (Exception | OutOfMemoryError e) {
            OopsHandler.collectStackTrace(e);
            return null;
        }
    }

    public void runOnUiThread(Runnable runnable) {
        uiThreadHandler.post(runnable);
    }

    public Song getCurrentSong() {
        synchronized (this) {
            return openedSong != null && playback != null && playback.isInitialized()
                    ? new Song(openedSong) : getSongAt(getPosition());
        }
    }

    private Song getSongAt(int position) {
        return getIndexedSongAt(position);
    }

    public IndexedSong getIndexedSongAt(int position) {
        synchronized (this) {
            if (position >= 0 && position < playingQueue.size()) {
                return playingQueue.getPlayingQueue().get(position);
            } else {
                return IndexedSong.EMPTY_INDEXED_SONG;
            }
        }
    }

    boolean isLastTrack() {
        synchronized (this) {
            return playingQueue.isLastTrack();
        }
    }

    public List<? extends Song> getPlayingQueue() {
        synchronized (this) {
            return playingQueue.getPlayingQueue();
        }
    }

    public int getRepeatMode() {
        synchronized (this) {
            return playingQueue.getRepeatMode();
        }
    }

    public void cycleRepeatMode() {
        synchronized (this) {
            playingQueue.cycleRepeatMode();
        }
        propagateRepeatChange();
    }

    private void propagateRepeatChange() {
        int repeatMode;
        synchronized (this) {
            repeatMode = playingQueue.getRepeatMode();
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(SAVED_REPEAT_MODE, repeatMode)
                .apply();
        prepareNext();
        handleAndSendChangeInternal(REPEAT_MODE_CHANGED);
    }

    private void propagateShuffleChange() {
        int shuffleMode;
        synchronized (this) {
            shuffleMode = playingQueue.getShuffleMode();
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(SAVED_SHUFFLE_MODE, shuffleMode)
                .apply();
        handleAndSendChangeInternal(SHUFFLE_MODE_CHANGED);
        notifyChange(QUEUE_CHANGED);
    }

    public int getShuffleMode() {
        synchronized (this) {
            return playingQueue.getShuffleMode();
        }
    }

    public void toggleShuffle() {
        synchronized (this) {
            playingQueue.toggleShuffle();
        }
        propagateShuffleChange();
    }

    public void setShuffleMode(final int shuffleMode) {
        synchronized (this) {
            playingQueue.setShuffle(shuffleMode);
        }
        propagateShuffleChange();
    }

    public void openQueue(@Nullable final Collection<? extends Song> queue, final int startPosition, final boolean startPlaying, final int shuffleMode) {
        int position;
        if (queue != null && shuffleMode != SHUFFLE_MODE_NONE && startPosition == RANDOM_START_POSITION_ON_SHUFFLE) {
            position = new Random().nextInt(queue.size());
        } else {
            position = startPosition;
        }

        synchronized (this) {
            if (playingQueue.openQueue(queue, position, shuffleMode)) {
                if (startPlaying) {
                    playSongAt(playingQueue.getCurrentPosition(), false);
                } else {
                    setPosition(position);
                }
                notifyChange(QUEUE_CHANGED);
            }
        }
    }

    public void openQueue(@Nullable final Collection<? extends Song> queue, final int startPosition, final boolean startPlaying) {
        synchronized (this) {
            openQueue(queue, startPosition, startPlaying, playingQueue.getShuffleMode());
        }
    }

    public void addSongAfter(int position, Song song) {
        synchronized (this) {
            playingQueue.addAfter(position, song);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void addSongBackTo(int position, IndexedSong song) {
        synchronized (this) {
            playingQueue.addSongBackTo(position, song);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void addSongsAfter(int position, Collection<? extends Song> songs) {
        synchronized (this) {
            playingQueue.addAllAfter(position, songs);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void addSong(Song song) {
        synchronized (this) {
            playingQueue.add(song);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void addSongs(Collection<? extends Song> songs) {
        synchronized (this) {
            playingQueue.addAll(songs);
            notifyChange(QUEUE_CHANGED);
        }
    }

    public void removeSong(int position) { // better to test is playing here and have only one signal than calling playNextSong and then removeSong (two signal need time in between to work ok)
        synchronized (this) {
            boolean isPlaying = isPlaying(playingQueue.getPlayingQueue().get(position));

            int newPosition = playingQueue.remove(position);
            if (playingQueue.size() == 0) {
                setPosition(StaticPlayingQueue.INVALID_POSITION);
            } else if (newPosition != -1) {
                if (isPlaying) {playSongAt(newPosition, false);}
                else {setPosition(newPosition);}
            }
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void removeSongs(@NonNull List<Song> songs) {
        synchronized (this) {
            int newPosition = playingQueue.removeSongs(songs);
            if (newPosition != -1 || playingQueue.size() == 0) {
                setPosition(newPosition);
            }
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void moveSong(int from, int to) {
        synchronized (this) {
            playingQueue.move(from, to);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void clearQueue() {
        synchronized (this) {
            playingQueue.clear();
            setPosition(-1);
        }
        notifyChange(QUEUE_CHANGED);
    }

    public void playSongAt(final int position, boolean skippedLast) {
        if (skippedLast && PreferenceUtil.getInstance().maintainSkippedSongsPlaylist()) {
            final int songProgressMs;
            final int songDurationMs;
            final Song song;
            synchronized (this) {
                songProgressMs = getSongProgressMillis();
                songDurationMs = getSongDurationMillis();
                song = getCurrentSong();
            }

            if ((songProgressMs > SKIP_THRESHOLD_MS) // not just started
                    && (songDurationMs - songProgressMs > SKIP_THRESHOLD_MS) // not about to end
            ) {
                // Mark the current song as skipped
                final long playlistId = MusicUtil.getOrCreateSkippedPlaylist(this).id;
                if (!PlaylistsUtil.doesPlaylistContain(playlistId, song.id)) {
                    PlaylistsUtil.addToPlaylist(this, song, playlistId, true);
                }
            }
        }

        synchronized (this) {
            if (playbackHandlerThread.isAlive()) {
                playbackHandler.removeMessages(PLAY_SONG);
                playbackHandler.obtainMessage(PLAY_SONG, position, 0).sendToTarget();
            }
        }
    }

    public void setPosition(final int position) {
        synchronized (this) {
            if (playbackHandlerThread.isAlive()) {
                playbackHandler.removeMessages(SET_POSITION);
                playbackHandler.obtainMessage(SET_POSITION, position, 0).sendToTarget();
            }
        }
    }

    void setPositionToNextPosition() {
        synchronized (this) {
            playingQueue.setPositionToNextPosition();
        }
    }

    void playSongAtImpl(int position) {
        if (openTrackAndPrepareNextAt(position)) {
            play();
        } else {
            onTrackUnplayable();
        }
    }

    /**
     * Handles a track that could not be opened or played: records it as
     * unplayable and advances the queue when it still makes sense to do so.
     */
    private void onTrackUnplayable() {
        final Song song = getCurrentSong();
        recordUnplayableTrack(song);

        if (song.id == Song.EMPTY_SONG.id) {
            SafeToast.show(this, getResources().getString(R.string.unplayable_file));
        } else {
            SafeToast.show(this, getResources().getString(
                    R.string.unplayable_file_skipped, song.getTitle()));
        }

        skipToNextPlayable(true);
    }

    private void recordUnplayableTrack(@NonNull Song song) {
        unplayableTracks.markUnplayable(getTrackUri(song));
        recoverySong = Song.EMPTY_SONG;
        if (playbackHandler != null) playbackHandler.removeMessages(CHECK_UNPLAYABLE_RECOVERY);
        try {
            UnplayableTrackStore.getInstance(this).recordFailure(song);
        } catch (Exception error) {
            // Reporting must never prevent the player from skipping a broken file.
            OopsHandler.collectStackTrace(error);
        }
    }

    /** Observe actual playback, not preparation or a seek, before dismissing a report. */
    void scheduleUnplayableTrackRecovery() {
        synchronized (this) {
            if (playbackHandler != null) playbackHandler.removeMessages(CHECK_UNPLAYABLE_RECOVERY);
            recoverySong = Song.EMPTY_SONG;
            try {
                Song song = getCurrentSong();
                if (!isPlaying() || unplayableTracks.isUnplayable(getTrackUri(song))
                        || !UnplayableTrackStore.getInstance(this).contains(song)
                        || !MusicUtil.isSongFileIdentityCurrent(this, song)) return;
                recoverySong = song;
                recoveryLastSampleAt = SystemClock.elapsedRealtime();
                recoveryLastPosition = getSongProgressMillis();
                recoveryProgressMillis = 0;
                if (playbackHandler != null) {
                    playbackHandler.sendEmptyMessageDelayed(CHECK_UNPLAYABLE_RECOVERY, 250);
                }
            } catch (RuntimeException error) {
                // Optional maintenance data must never interrupt normal playback.
                recoverySong = Song.EMPTY_SONG;
                OopsHandler.collectStackTrace(error);
            }
        }
    }

    private long recoveryInterval(@NonNull Song song) {
        return song.duration > 0 ? Math.max(1, Math.min(SKIP_RESET_PROGRESS_MS, song.duration / 2))
                : SKIP_RESET_PROGRESS_MS;
    }

    void checkForRecoveredTrack() {
        synchronized (this) {
            Song song = getCurrentSong();
            if (recoverySong.id == Song.EMPTY_SONG.id || !isPlaying()
                    || song.id != recoverySong.id || !song.data.equals(recoverySong.data)
                    || unplayableTracks.isUnplayable(getTrackUri(song))) return;
            try {
                long now = SystemClock.elapsedRealtime();
                long elapsed = now - recoveryLastSampleAt;
                int position = getSongProgressMillis();
                long advance = (long) position - recoveryLastPosition;
                // Ignore seek jumps, stalls, and long gaps where playback was not observed.
                if (elapsed > 0 && elapsed <= 2000 && advance > 0 && advance <= elapsed + 250) {
                    recoveryProgressMillis += Math.min(elapsed, advance);
                }
                recoveryLastSampleAt = now;
                recoveryLastPosition = position;
                if (recoveryProgressMillis >= recoveryInterval(song)) {
                    if (MusicUtil.isSongFileIdentityCurrent(this, song)) {
                        UnplayableTrackStore.getInstance(this).remove(song);
                    }
                    recoverySong = Song.EMPTY_SONG;
                } else if (playbackHandler != null) {
                    playbackHandler.sendEmptyMessageDelayed(CHECK_UNPLAYABLE_RECOVERY, 250);
                }
            } catch (RuntimeException error) {
                recoverySong = Song.EMPTY_SONG;
                OopsHandler.collectStackTrace(error);
            }
        }
    }

    /**
     * Advances to the next playable track, keeping within the consecutive
     * failure cap and honoring the queue boundaries.
     */
    void skipToNextPlayable(boolean skippedLast) {
        final int nextPosition;
        synchronized (this) {
            consecutiveSkipCount++;

            // Repeating a single broken track, or the last track of a
            // non-repeating queue, would either loop forever or wrap
            // unexpectedly: stop cleanly instead.
            if (getRepeatMode() == REPEAT_MODE_THIS
                    || (getRepeatMode() == REPEAT_MODE_NONE && playingQueue.isLastTrack())) {
                notifyChange(PLAY_STATE_CHANGED);
                return;
            }

            if (consecutiveSkipCount >= MAX_CONSECUTIVE_SKIPS) {
                stopAfterTooManyFailures();
                return;
            }

            nextPosition = playingQueue.getNextPosition(skippedLast);
        }

        playSongAtImpl(nextPosition);
    }

    void stopAfterTooManyFailures() {
        SafeToast.show(this, R.string.too_many_unplayable_files);
        notifyChange(PLAY_STATE_CHANGED);
    }

    /**
     * Clears the consecutive-skip counter once a track has actually been heard.
     *
     * <p>Called when a track starts or changes, never from the failure path:
     * a failed track reports no progress, so measuring there would never
     * reset anything and the cap would become a session-wide total.
     */
    void resetConsecutiveSkipsIfPlayed() {
        synchronized (this) {
            if (getSongProgressMillis() >= SKIP_RESET_PROGRESS_MS) {
                consecutiveSkipCount = 0;
            }
        }
    }

    public void pause() {
        synchronized (this) {
            pausedByTransientLossOfFocus = false;
            if (playback.isPlaying()) {
                playback.pause();
                notifyChange(PLAY_STATE_CHANGED);
            }
        }
    }

    public void play() {
        synchronized (this) {
            if (requestFocus()) {
                if (!playback.isPlaying()) {
                    if (!playback.isInitialized()) {
                        playSongAt(getPosition(), false);
                    } else {
                        playback.start();
                        if (!becomingNoisyReceiverRegistered) {
                            registerReceiver(becomingNoisyReceiver, becomingNoisyReceiverIntentFilter, Context.RECEIVER_NOT_EXPORTED);
                            becomingNoisyReceiverRegistered = true;
                        }
                        if (notHandledMetaChangedForCurrentTrack) {
                            handleChangeInternal(META_CHANGED);
                            notHandledMetaChangedForCurrentTrack = false;
                        }
                        notifyChange(PLAY_STATE_CHANGED);

                        // fixes a bug where the volume would stay ducked because the AudioManager.AUDIOFOCUS_GAIN event is not sent
                        if (playbackHandlerThread.isAlive()) {
                            playbackHandler.removeMessages(DUCK);
                            playbackHandler.sendEmptyMessage(UNDUCK);
                        }
                    }
                }
            } else {
                SafeToast.show(this, getResources().getString(R.string.audio_focus_denied));
            }
        }
    }

    private void applyReplayGain() {
        synchronized (this) {
            if (playback == null) {return;}

            final String mode = PreferenceUtil.getInstance().getReplayGainSourceMode();
            if (!mode.equals(PreferenceUtil.RG_SOURCE_MODE_NONE)) {
                Song song = getCurrentSong();

                float adjustDB = 0.0f;
                float peak = 1.0f;

                float rgTrack = song.replayGainTrack;
                float rgAlbum = song.replayGainAlbum;
                float rgpTrack = song.replayGainPeakTrack;
                float rgpAlbum = song.replayGainPeakAlbum;

                if (mode == PreferenceUtil.RG_SOURCE_MODE_ALBUM) {
                    adjustDB = (rgTrack == 0.0f ? adjustDB : rgTrack);
                    adjustDB = (rgAlbum == 0.0f ? adjustDB : rgAlbum);
                    peak = (rgpTrack == 1.0f ? peak : rgpTrack);
                    peak = (rgpAlbum == 1.0f ? peak : rgpAlbum);
                } else if (mode == PreferenceUtil.RG_SOURCE_MODE_TRACK) {
                    adjustDB = (rgAlbum == 0.0f ? adjustDB : rgAlbum);
                    adjustDB = (rgTrack == 0.0f ? adjustDB : rgTrack);
                    peak = (rgpAlbum == 1.0f ? peak : rgpAlbum);
                    peak = (rgpTrack == 1.0f ? peak : rgpTrack);
                }

                if (adjustDB == 0) {
                    adjustDB = PreferenceUtil.getInstance().getRgPreampWithoutTag();
                } else {
                    adjustDB += PreferenceUtil.getInstance().getRgPreampWithTag();

                    float peakDB = -20.0f * ((float) Math.log10(peak));
                    adjustDB = Math.min(adjustDB, peakDB);
                }

                playback.setReplayGain(adjustDB);
            } else {
                playback.setReplayGain(Float.NaN);
            }
        }
    }

    public void playPreviousSong(boolean skippedLast) {
        synchronized (this) {
            playSongAt(playingQueue.getPreviousPosition(skippedLast), skippedLast);
        }
    }

    public void back(boolean skippedLast) {
        if (getSongProgressMillis() > SKIP_THRESHOLD_MS) {
            seek(0);
        } else {
            playPreviousSong(skippedLast);
        }
    }

    public int getSongProgressMillis() {
        synchronized (this) {
            return (playback != null ? playback.position() : -1);
        }
    }

    public int getSongDurationMillis() {
        synchronized (this) {
            return (playback != null ? playback.duration() : -1);
        }
    }

    private long getQueueDurationMillis(int position) {
        synchronized (this) {
            return playingQueue.getQueueDurationMillis(position);
        }
    }

    public void seek(int millis) {
        synchronized (this) {
            if (playback != null) {
                playback.seek(millis);
            }
        }
        throttledSeekHandler.notifySeek();
    }

    void notifyChange(@NonNull final String what) {
        handleAndSendChangeInternal(what);
        sendPublicIntent(what);
    }

    void handleAndSendChangeInternal(@NonNull final String what) {
        handleChangeInternal(what);
        sendChangeInternal(what);
    }

    // to let other apps know whats playing. i.E. last.fm (scrobbling) or musixmatch
    void sendPublicIntent(@NonNull final String what) {
        final Intent intent = new Intent(what.replace(NEEDLE_MUSIC_PLAYER_PACKAGE_NAME, MUSIC_PACKAGE_NAME));

        final Song song = getCurrentSong();

        intent.putExtra("id", song.id);

        intent.putExtra("artist", MultiValuesTagUtil.infoStringAsArtists(song.artistNames));
        intent.putExtra("album", Album.getTitle(song.albumName));
        intent.putExtra("track", song.getTitle());

        intent.putExtra("duration", song.duration);
        intent.putExtra("position", (long) getSongProgressMillis());

        intent.putExtra("playing", isPlaying());

        intent.putExtra("scrobbling_source", NEEDLE_MUSIC_PLAYER_PACKAGE_NAME);

        sendStickyBroadcast(intent);
    }

    private void sendChangeInternal(final String what) {
        sendBroadcast(new Intent(what).setPackage(getPackageName()));
        appWidgetBig.notifyChange(this, what);
        appWidgetClassic.notifyChange(this, what);
        appWidgetSmall.notifyChange(this, what);
        appWidgetCard.notifyChange(this, what);
    }

    private static final long MEDIA_SESSION_ACTIONS = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_SEEK_TO;

    private void handleChangeInternal(@NonNull final String what) {
        switch (what) {
            case PLAY_STATE_CHANGED:
                updateNotification();
                updateMediaSessionPlaybackState();
                final boolean isPlaying = isPlaying();
                if (!isPlaying && getSongProgressMillis() > 0) {
                    savePositionInTrack();
                }
                songPlayCountHelper.notifyPlayStateChanged(isPlaying);
                scheduleUnplayableTrackRecovery();
                break;
            case FAVORITE_STATE_CHANGED:
                updateNotification();
                break;
            case META_CHANGED:
                updateNotification();
                updateMediaSessionMetaData();
                updateMediaSessionPlaybackState();

                savePosition();
                savePositionInTrack();
                applyReplayGain();

                final Song currentSong = getCurrentSong();
                HistoryStore.getInstance(this).addSongId(currentSong.id);
                if (PreferenceUtil.getInstance().maintainTopTrackPlaylist()) {
                    SongPlayCountStore store = SongPlayCountStore.getInstance(this);
                    if (songPlayCountHelper.commitIfQualified(store, songPlayCountHelper.getElapsedTime())) {
                        sendChangeInternal(STATISTICS_CHANGED);
                    }
                }
                songPlayCountHelper.notifySongChanged(currentSong, SongPlayCountStore.getInstance(this));
                scheduleUnplayableTrackRecovery();
                break;
            case QUEUE_CHANGED:
                updateMediaSessionMetaData(); // because playing queue size might have changed
                saveState();
                int queueSize = 0;
                synchronized (this) {
                    queueSize = playingQueue.size();
                }

                if (queueSize > 0) {
                    prepareNext();
                } else {
                    updateNotification();
                }
                break;
        }
    }

    public int getAudioSessionId() {
        synchronized (this) {
            return (playback != null ? playback.getAudioSessionId() : -1);
        }
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    void releaseWakeLock() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void acquireWakeLock(long milli) {
        wakeLock.acquire(milli);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, @Nullable final String key) {
        if (TextUtils.equals(key, PreferenceUtil.GAPLESS_PLAYBACK)) {
            synchronized (this) {
                if (sharedPreferences.getBoolean(key, false)) {
                    prepareNext();
                } else {
                    if (playback != null) {playback.setNextDataSource(null);}
                }
            }
        } else if (TextUtils.equals(key, PreferenceUtil.COLORED_NOTIFICATION)) {
            updateNotification();
        } else if (TextUtils.equals(key, PreferenceUtil.OOPS_HANDLER_ENABLED)
                || TextUtils.equals(key, PreferenceUtil.OOPS_HANDLER_EXCEPTIONS)
        ) {
            updateCrashNotification();
        } else if (TextUtils.equals(key, PreferenceUtil.CLASSIC_NOTIFICATION)) {
            initNotification();
            updateNotification();
        } else if (TextUtils.equals(key, PreferenceUtil.TRANSPARENT_BACKGROUND_WIDGET)) {
            sendChangeInternal(META_CHANGED);
        } else if (TextUtils.equals(key, PreferenceUtil.RG_SOURCE_MODE_V2)
                || TextUtils.equals(key, PreferenceUtil.RG_PREAMP_WITH_TAG)
                || TextUtils.equals(key, PreferenceUtil.RG_PREAMP_WITHOUT_TAG)
        ) {
            applyReplayGain();
        }
    }

    @Override
    public void onTrackWentToNext(long sourceToken) {
        synchronized (this) {
            if (playbackHandlerThread.isAlive()) {
                PreparedSource promoted = preparedSources.remove(sourceToken);
                if (promoted != null) {
                    playbackHandler.obtainMessage(TRACK_WENT_TO_NEXT, promoted).sendToTarget();
                } else {
                    // Do not continue unidentified audio after a stale/unknown promotion.
                    playback.stop();
                }
            }
        }
    }

    void promotePreparedSource(PreparedSource promoted) {
        synchronized (this) {
            openedSong = new Song(promoted.song);
            // Queue positions can have changed since this source was prepared.
            int position = promoted.position;
            if (position < 0 || position >= playingQueue.size()
                    || !io.github.omblivion.needle.model.SongSource.from(getSongAt(position))
                    .sameTrack(io.github.omblivion.needle.model.SongSource.from(promoted.song))) {
                position = -1;
                for (int i = 0; i < playingQueue.size(); i++) {
                    if (io.github.omblivion.needle.model.SongSource.from(getSongAt(i))
                            .sameTrack(io.github.omblivion.needle.model.SongSource.from(promoted.song))) {
                        position = i;
                        break;
                    }
                }
            }
            if (position >= 0) playingQueue.setCurrentPosition(position);
        }
    }

    @Override
    public void onTrackEnded() {
        acquireWakeLock(30000);
        synchronized (this) {
            if (playbackHandlerThread.isAlive()) {
                playbackHandler.sendEmptyMessage(TRACK_ENDED);
            }
        }
    }

    @Override
    public void onTrackFailed() {
        synchronized (this) {
            recordUnplayableTrack(getCurrentSong());
        }
        requestSkipOfUnplayableTrack();
    }

    /**
     * Asks the playback handler to skip the failed track. Overridable so tests
     * can observe the request without a live handler thread.
     */
    void requestSkipOfUnplayableTrack() {
        synchronized (this) {
            if (playbackHandlerThread != null && playbackHandlerThread.isAlive()) {
                // One pending skip is enough: a second failure report for the
                // same broken track must not advance twice.
                playbackHandler.removeMessages(TRACK_FAILED);
                playbackHandler.obtainMessage(TRACK_FAILED).sendToTarget();
            }
        }
    }

    public class MusicBinder extends Binder {
        @NonNull
        public MusicService getService() {
            return MusicService.this;
        }
    }

    private final BroadcastReceiver widgetIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String command = intent.getStringExtra(EXTRA_APP_WIDGET_NAME);
            if (command == null) {return;}

            final int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            switch (command) {
                case AppWidgetClassic.NAME: {
                    appWidgetClassic.performUpdate(MusicService.this, ids);
                    break;
                }
                case AppWidgetSmall.NAME: {
                    appWidgetSmall.performUpdate(MusicService.this, ids);
                    break;
                }
                case AppWidgetBig.NAME: {
                    appWidgetBig.performUpdate(MusicService.this, ids);
                    break;
                }
                case AppWidgetCard.NAME: {
                    appWidgetCard.performUpdate(MusicService.this, ids);
                    break;
                }
            }
        }
    };

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        // Check origin to ensure we're not allowing any arbitrary app to browse app contents
        if (!mPackageValidator.isKnownCaller(clientPackageName, clientUid)) {
            return null;
        }

        // System UI query (Android 11+)
        Predicate<Bundle> isSystemMediaQuery = (hints) -> {
            if (hints == null) {return false;}
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {return false;}
            if (hints.getBoolean(BrowserRoot.EXTRA_RECENT)) {return true;}
            if (hints.getBoolean(BrowserRoot.EXTRA_SUGGESTED)) {return true;}
            if (hints.getBoolean(BrowserRoot.EXTRA_OFFLINE)) {return true;}
            return false;
        };
        if (isSystemMediaQuery.test(rootHints)) {
            // By returning null, we explicitly disable support for content discovery/suggestions
            return null;
        }

        return new BrowserRoot(BrowsableMediaIDHelper.MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(@NonNull final String parentId, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(mBrowsableMusicProvider.getChildren(parentId, getResources()));
    }

    Playback getPlayback() {
        synchronized (this) {
            return playback;
        }
    }

    void setPausedByTransientLossOfFocus(boolean pausedByTransientLossOfFocus) {
        synchronized (this) {
            this.pausedByTransientLossOfFocus = pausedByTransientLossOfFocus;
        }
    }

    boolean isPausedByTransientLossOfFocus() {
        synchronized (this) {
            return pausedByTransientLossOfFocus;
        }
    }

    @NonNull
    public String getQueueInfoString() {
        final long duration;
        final int position;
        final int size;
        synchronized (this) {
            duration = getQueueDurationMillis(getPosition());
            position = getPosition() + 1;
            size = playingQueue.size();
        }
        return MusicUtil.buildInfoString(
                getResources().getString(R.string.up_next),
                MusicUtil.getReadableDurationString(duration),
                position + "/" + size
        );
    }

    private void onDiscographyChanged() {
        if (playbackHandlerThread.isAlive()) {
            playbackHandler.post(this::reconcileLibrary);
        }
    }

    private void reconcileLibrary() {
        synchronized (this) {
            io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
                Discography discography = Discography.getInstance();
                playingQueue.reconcileSources(discography.getAllSongsById(),
                        discography.getConfirmedDeletedIds(),
                        PreferenceUtil.getInstance().isQueueSyncWithMediaStoreEnabled());
                if (openedSong != null) {
                    Song logical = discography.resolveReference(openedSong);
                    if (logical.id == Song.EMPTY_SONG.id) {
                        playback.stop();
                        openedSong = null;
                        openedSource = null;
                    } else if (openedSource == null
                            || openedSource.versionChanged(io.github.omblivion.needle.model.SongSource.from(logical))) {
                        // The open audio no longer matches the catalog entry: reopen
                        // rather than describing old audio with new metadata.
                        openedSong = null;
                        openedSource = null;
                        openCurrent();
                    } else {
                        openedSong = new Song(logical);
                    }
                }
                prepareNextImpl();
                saveQueuesImpl();
                return null;
            });
            notifyChange(QUEUE_CHANGED);
            updateNotification();
            updateMediaSessionMetaData();
            sendChangeInternal(META_CHANGED);
        }
    }
}
