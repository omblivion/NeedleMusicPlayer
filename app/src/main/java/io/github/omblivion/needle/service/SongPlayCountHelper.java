package io.github.omblivion.needle.service;

import androidx.annotation.NonNull;

import android.os.SystemClock;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.ReferenceKey;
import io.github.omblivion.needle.provider.SongPlayCountStore;

import java.util.UUID;

class SongPlayCountHelper {
    public static final String TAG = io.github.omblivion.needle.service.SongPlayCountHelper.class.getSimpleName();

    private long elapsed;
    private long startedAt = -1;
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
        return getElapsedTime(SystemClock.elapsedRealtime());
    }

    synchronized long getElapsedTime(long at) {
        return elapsed + (startedAt < 0 ? 0 : Math.max(0, at - startedAt));
    }

    boolean shouldBumpPlayCount() {
        return shouldBumpPlayCount(getElapsedTime());
    }

    boolean shouldBumpPlayCount(long elapsedMs) {
        return song.duration * 0.5d < elapsedMs;
    }

    void notifySongChanged(@NonNull Song song, @NonNull SongPlayCountStore store) {
        synchronized (this) {
            elapsed = 0;
            startedAt = -1;
            this.song = song;
            this.occurrence = UUID.randomUUID().toString();
            this.resetVersion = store.getResetVersion(ReferenceKey.of(song));
            this.committed = false;
        }
    }

    void notifyPlayStateChanged(boolean isPlaying) {
        notifyPlayStateChanged(isPlaying, SystemClock.elapsedRealtime());
    }

    void notifyPlayStateChanged(boolean isPlaying, long at) {
        synchronized (this) {
            if (isPlaying && startedAt < 0) {
                startedAt = at;
            } else if (!isPlaying && startedAt >= 0) {
                elapsed = getElapsedTime(at);
                startedAt = -1;
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
            return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            if (committed || occurrence == null || !shouldBumpPlayCount(elapsedMs)) {
                return false;
            }
            committed = true;
            Song logical = io.github.omblivion.needle.discog.Discography.getInstance().resolveReference(song);
            if (logical.id == Song.EMPTY_SONG.id) return false;
            store.recordPlay(ReferenceKey.of(logical), System.currentTimeMillis(), occurrence, resetVersion);
            return true;
            });
        }
    }
}
