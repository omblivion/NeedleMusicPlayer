package io.github.omblivion.needle.service;

import androidx.annotation.NonNull;

import io.github.omblivion.needle.helper.StopWatch;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.SongPlayCountStore;

import java.util.UUID;

class SongPlayCountHelper {
    public static final String TAG = io.github.omblivion.needle.service.SongPlayCountHelper.class.getSimpleName();

    private final StopWatch stopWatch = new StopWatch();
    private Song song = Song.EMPTY_SONG;

    // Captured when tracking begins for a new song: a unique occurrence token
    // and the song's reset version at that moment. The version lets a session
    // that spans a reset avoid resurrecting a cleared count.
    private String occurrence;
    private long resetVersion;
    private boolean committed;

    public Song getSong() {
        return song;
    }

    public long getElapsedTime() {
        return stopWatch.getElapsedTime();
    }

    boolean shouldBumpPlayCount() {
        return shouldBumpPlayCount(stopWatch.getElapsedTime());
    }

    boolean shouldBumpPlayCount(long elapsedMs) {
        return song.duration * 0.5d < elapsedMs;
    }

    void notifySongChanged(@NonNull Song song, @NonNull SongPlayCountStore store) {
        synchronized (this) {
            stopWatch.reset();
            this.song = song;
            this.occurrence = UUID.randomUUID().toString();
            this.resetVersion = store.getResetVersion(song.id);
            this.committed = false;
        }
    }

    void notifyPlayStateChanged(boolean isPlaying) {
        synchronized (this) {
            if (isPlaying) {
                stopWatch.start();
            } else {
                stopWatch.pause();
            }
        }
    }

    /**
     * Records this session's play at most once, when it qualifies. The captured
     * occurrence makes the write idempotent and the captured reset version makes
     * a session that began before a reset harmless.
     *
     * @return whether this call recorded the play
     */
    boolean commitIfQualified(@NonNull SongPlayCountStore store, long elapsedMs) {
        synchronized (this) {
            if (committed || occurrence == null || !shouldBumpPlayCount(elapsedMs)) {
                return false;
            }
            committed = true;
            store.recordPlay(song.id, System.currentTimeMillis(), occurrence, resetVersion);
            return true;
        }
    }
}
