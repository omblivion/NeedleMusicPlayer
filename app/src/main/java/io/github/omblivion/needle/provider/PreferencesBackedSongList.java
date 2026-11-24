package io.github.omblivion.needle.provider;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.R;
import io.github.omblivion.needle.discog.Discography;
import io.github.omblivion.needle.misc.queue.IndexedSong;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.util.PrefKey;
import io.github.omblivion.needle.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Ordered list of songs, backed by a persistent storage
 * Can be used for playlist, play queue
 *
 * @author SC (soncaokim)
 */
abstract class SongList {
    @NonNull public String name;
    final List<io.github.omblivion.needle.provider.ReferenceKey> entries = new ArrayList<>();

    SongList(@NonNull String name) {
        this.name = name;
        load();
    }

    abstract void load();
    abstract void save(@Nullable String newName);

    @NonNull
    public String getName() {return name;}

    public boolean contains(long songId) {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            load();
            for (io.github.omblivion.needle.provider.ReferenceKey entry : entries) {
                if (entry.id() == songId) return true;
            }
            return false;
        });
    }

    /** Resolves a source-bearing song to the key it should be stored under. */
    static List<io.github.omblivion.needle.provider.ReferenceKey> resolveKeys(@NonNull List<Song> songs) {
        List<io.github.omblivion.needle.provider.ReferenceKey> keys = new ArrayList<>();
        for (Song song : songs) {
            if (song.id == Song.EMPTY_SONG.id) continue;
            // The path is the identity, so store it alongside the current ID.
            keys.add(new io.github.omblivion.needle.provider.ReferenceKey(song.id, song.data));
        }
        return keys;
    }

    @NonNull
    public List<? extends Song> asSongs() {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(this::readSongs);
    }

    private List<? extends Song> readSongs() {
        load();
        ArrayList<Song> result = new ArrayList<>();
        ArrayList<Long> orphanIds = new ArrayList<>();

        // Since the song list is decoupled from Discography, we need to check its content
        // against the valid songs in discog. Resolution is by path only: a numeric
        // ID is never used, so a recycled provider ID cannot claim another track's
        // reference. An entry whose path is temporarily absent is simply not shown.
        final Map<Long, Song> availableSongsById = Discography.getInstance().getAllSongsById();
        final Map<String, Song> availableByPath = new java.util.HashMap<>();
        for (Song song : availableSongsById.values()) {
            availableByPath.put(io.github.omblivion.needle.model.SongSource.canonicalPath(song.data), song);
        }
        for (final io.github.omblivion.needle.provider.ReferenceKey entry : entries) {
            if (entry.path().isEmpty()) continue;
            Song matchingSong = availableByPath.get(
                    io.github.omblivion.needle.model.SongSource.canonicalPath(entry.path()));
            if (matchingSong != null) {
                result.add(matchingSong);
            }
        }

        return result;
    }
}

abstract class MutableSongList extends SongList {
    MutableSongList(@NonNull String name) {
        super(name);
    }

    public void addSongs(@NonNull List<Song> songs) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
        load();
        entries.addAll(resolveKeys(songs));
        save(null);
        return null;
        });
    }

    public void removeSong(long id) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
        load();
        entries.removeIf(entry -> entry.id() == id);
        save(null);
        return null;
        });
    }

    public void removeSongsAtPosition(@NonNull final List<Integer> positions) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
        load();
        final List<Integer> reversedPositions = new ArrayList<>(positions);
        reversedPositions.sort(Comparator.reverseOrder());
        for (final int position : reversedPositions) {
            if (position >= 0 && position < entries.size()) {
                entries.remove(position);
            }
        }
        save(null);
        return null;
        });
    }

    public boolean moveSong(int fromPosition, int toPosition) {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() ->
                moveSongInternal(fromPosition, toPosition));
    }

    private boolean moveSongInternal(int fromPosition, int toPosition) {
        load();
        if (fromPosition == toPosition) {return true;}

        final int size = entries.size();
        if (fromPosition < 0 || fromPosition >= size) {return false;}
        if (toPosition < 0 || toPosition >= size) {return false;}

        final io.github.omblivion.needle.provider.ReferenceKey movedSong = entries.get(fromPosition);
        entries.remove(fromPosition);

        final int toPositionShift = fromPosition < toPosition ? -1 : 0;
        entries.add(toPosition + toPositionShift, movedSong);

        save(null);

        return true;
    }

    public void rename(@NonNull final String newName) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            load();
            save(newName);
            return null;
        });
    }
}

public class PreferencesBackedSongList extends MutableSongList {
    private static final String SEPARATOR = ",";
    static final String PREF_NAME_PREFIX = PrefKey.nonExportablePrefixedKey("SONG_IDS_");

    private static SharedPreferences preferences = null;
    static SharedPreferences getPreferences() {
        if (preferences == null) {
            preferences = PreferenceManager.getDefaultSharedPreferences(App.getStaticContext());
        }
        return preferences;
    }

    /** Every reference key stored across all app-owned song lists. */
    static java.util.Set<io.github.omblivion.needle.provider.ReferenceKey> allStoredKeys() {
        return io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            java.util.Set<io.github.omblivion.needle.provider.ReferenceKey> keys = new java.util.HashSet<>();
            final SharedPreferences preferences = getPreferences();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(PREF_NAME_PREFIX)) continue;
                for (String token : ((String) entry.getValue()).split(SEPARATOR)) {
                    if (token.isEmpty()) continue;
                    try {
                        keys.add(io.github.omblivion.needle.provider.ReferenceKey.parse(token));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed entry cannot be resolved; leave it in place.
                    }
                }
            }
            return keys;
        });
    }

    static List<PreferencesBackedSongList> loadAll() {        ArrayList<PreferencesBackedSongList> result = new ArrayList<>();

        String favoritesPlaylistName = App.getStaticContext().getString(R.string.favorites);
        PreferencesBackedSongList favoritesPlaylist = null;

        final SharedPreferences preferences = getPreferences();
        for (String prefName : preferences.getAll().keySet()) {
            if (prefName.startsWith(PREF_NAME_PREFIX)) {
                final String name = prefName.substring(PREF_NAME_PREFIX.length());
                if (name.equals(favoritesPlaylistName)){
                    favoritesPlaylist = new PreferencesBackedSongList(name);
                    continue;
                }
                result.add(new PreferencesBackedSongList(name));
            }
        }

        Collections.sort(result, (l1, l2) -> StringUtil.compareIgnoreAccent(l1.name, l2.name));

        if (favoritesPlaylist != null)
            result.add(0, favoritesPlaylist);

        return result;
    }

    static void remove(@NonNull String name) {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
            getPreferences().edit().remove(PREF_NAME_PREFIX + name).commit();
            return null;
        });
    }

    PreferencesBackedSongList(@NonNull String name) {
        super(name);
    }

    @Override
    void load() {
        io.github.omblivion.needle.discog.ReferenceReconciliationJournal.access(() -> {
        final SharedPreferences preferences = getPreferences();
        String values = preferences.getString(PREF_NAME_PREFIX + name, "");

        entries.clear();
        for (String token : values.split(SEPARATOR)) {
            if (token.isEmpty()) continue;
            try {
                entries.add(io.github.omblivion.needle.provider.ReferenceKey.parse(token));
            } catch (IllegalArgumentException ignored) {
                // A malformed entry is skipped rather than losing the whole list.
            }
        }
        return null;
        });
    }

    @Override
    void save(@Nullable String newName) {
        StringBuilder values = new StringBuilder();
        for (io.github.omblivion.needle.provider.ReferenceKey entry : entries) {
            if (values.length() > 0) {values.append(SEPARATOR);}
            values.append(entry.serialize());
        }

        final SharedPreferences preferences = getPreferences();
        if (newName != null) {
            preferences.edit().remove(PREF_NAME_PREFIX + name).apply();
            name = newName;
        }
        preferences.edit().putString(PREF_NAME_PREFIX + name, values.toString()).apply();
    }
}

class PreferenceBackedReorderableSongList extends PreferencesBackedSongList {
    PreferenceBackedReorderableSongList(@NonNull final String name) {
        super(name);
    }

    // Assign a stable and unique ID to each song in the list. That ID can then be used as UI RecycleView's ID
    // - to be unique: the Song's id cannot be used since the list can contain duplicate of same song
    // - to be stable: the postition of the song in the list cannot be used as an ID since the song can be moved (hence the position changes)

    private long nextUniqueId = 0L;

    @Override
    @NonNull
    public List<? extends Song> asSongs() {
        final List<? extends Song> songs = super.asSongs();
        final int count = songs.size();

        final ArrayList<IndexedSong> indexedSongs = new ArrayList<>(count);
        for (int i=0; i<count; ++i) {
            ++nextUniqueId;
            indexedSongs.add(new IndexedSong(songs.get(i), i, nextUniqueId));
        }

        return indexedSongs;
    }
}
