package io.github.omblivion.needle.interfaces;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public interface MusicServiceEventListener {
    void onServiceConnected();

    void onServiceDisconnected();

    void onQueueChanged();

    void onPlayingMetaChanged();

    void onPlayStateChanged();

    void onRepeatModeChanged();

    void onShuffleModeChanged();

    void onMediaStoreChanged();

    /** Called when play statistics change; most-played views refresh here. */
    default void onStatisticsChanged() {
    }

    /** Called when persistent playback-error reports change. */
    default void onUnplayableTracksChanged() {
    }
}
