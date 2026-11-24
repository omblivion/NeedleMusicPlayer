package io.github.omblivion.needle.discog;

import android.content.Context;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.omblivion.needle.App;
import io.github.omblivion.needle.discog.tagging.TagExtractor;
import io.github.omblivion.needle.interfaces.MusicServiceEventListener;
import io.github.omblivion.needle.model.Album;
import io.github.omblivion.needle.model.Artist;
import io.github.omblivion.needle.model.Genre;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.provider.BlacklistStore;
import io.github.omblivion.needle.provider.LibraryReferenceStores;
import io.github.omblivion.needle.sort.SongSortOrder;
import io.github.omblivion.needle.ui.activities.base.AbsMusicServiceActivity;
import io.github.omblivion.needle.util.FileUtil;
import io.github.omblivion.needle.util.PreferenceUtil;
import io.github.omblivion.needle.util.StringUtil;

import org.jaudiotagger.tag.reference.GenreTypes;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * @author SC (soncaokim)
 */

public class Discography implements MusicServiceEventListener {
    // TODO wrap this inside the MemCache class
    private final DB database = new DB();
    private final MemCache cache = new MemCache();
    private long revision;
    private LibraryScanCoordinator scanCoordinator;
    private final LibraryRemovalTracker removalTracker = new LibraryRemovalTracker();
    private ReferenceReconciliationJournal referenceJournal;
    private boolean journalRecovered;
    private final Map<String, Integer> metadataAttempts = new HashMap<>();
    private final Set<String> confirmedDeletedSources = new HashSet<>();

    public void recoverReferences() {
        if (journalRecovered) return;
        if (referenceJournal == null) referenceJournal = new ReferenceReconciliationJournal();
        ReferenceReconciliationJournal.Pending pending = referenceJournal.read();
        if (pending != null) {
            Map<Long, Song> previous = new HashMap<>();
            for (Song song : database.fetchAllSongs()) previous.put(song.id, song);
            // Resolve the keys actually stored by each store, against the target
            // catalog, so the recovery matches what a normal publication would do.
            ReferencePlan plan = LibraryReferenceStores.planFor(pending.snapshot.songs,
                    pending.snapshot.confirmedDeletedPaths);
            LibraryReferenceStores.apply(pending.operation, plan, pending.snapshot.songs);
            database.replaceSongs(pending.snapshot.songs.values());
            synchronized (cache) {
                cache.replaceWith(pending.snapshot.prepared);
                cache.consistencyState = MemCache.ConsistencyState.OK;
                rememberConfirmedDeletions(pending.snapshot.confirmedDeleted, previous);
                revision++;
            }
            referenceJournal.complete();
        }
        journalRecovered = true;
        notifyDiscographyChanged();
    }

    boolean hasPendingRemovals() { return removalTracker.hasPending(); }

    public synchronized LibraryScanCoordinator getScanCoordinator() {
        if (scanCoordinator == null) {
            scanCoordinator = LibraryScanCoordinator.create(App.getStaticContext(), this);
        }
        return scanCoordinator;
    }

    public synchronized void ensureLoaded() {
        ReferenceReconciliationJournal.access(() -> {
            if (getCacheState() == MemCache.ConsistencyState.UNINITIALIZED) fetchAllSongs();
            return null;
        });
    }

    public long getRevision() {
        synchronized (cache) {
            return revision;
        }
    }

    public void publishSnapshot(@NonNull LibrarySnapshot snapshot) {
        ReferenceReconciliationJournal.access(() -> {
        synchronized (cache) {
            if (snapshot.baseRevision != revision) {
                throw new IllegalStateException("Obsolete library snapshot");
            }
            Map<Long, Song> beforePublication = new HashMap<>(cache.songsById);
            ReferencePlan plan = LibraryReferenceReconciler.derive(
                    referenceKeysFromCatalog(beforePublication), snapshot.songs,
                    snapshot.confirmedDeletedPaths);
            String operation = null;
            if (!plan.isEmpty()) {
                if (referenceJournal == null) referenceJournal = new ReferenceReconciliationJournal();
                operation = referenceJournal.begin(snapshot);
                journalRecovered = false;
            }
            // Publish first: from here the path is the only truth, and the plan
            // was computed from the pre-publication catalog.
            database.replaceSongs(snapshot.songs.values());
            cache.replaceWith(snapshot.prepared);
            rememberConfirmedDeletions(snapshot.confirmedDeleted, beforePublication);
            cache.consistencyState = MemCache.ConsistencyState.OK;
            revision++;
            if (operation != null) {
                ReferenceReconciliationJournal.applyGate.beforeApply();
                LibraryReferenceStores.apply(operation, plan, snapshot.songs);
                referenceJournal.complete();
                journalRecovered = true;
            }
        }
        return null;
        });
        notifyDiscographyChanged();
    }

    private final Map<AbsMusicServiceActivity, Handler> attachedActivitiesAndQueue = new HashMap<>();
    private static final String TASK_QUEUE_COALESCENCE_TOKEN = "Discography.triggerSyncWithMediaStore";
    private final Map<Runnable, Handler> changedListeners = new java.util.concurrent.ConcurrentHashMap<>();

    private static Set<io.github.omblivion.needle.provider.ReferenceKey> referenceKeysFromCatalog(
            Map<Long, Song> catalog) {
        Set<io.github.omblivion.needle.provider.ReferenceKey> keys = new HashSet<>();
        for (Song song : catalog.values()) {
            keys.add(new io.github.omblivion.needle.provider.ReferenceKey(song.id, song.data));
        }
        return keys;
    }

    /** Remembers which source a confirmed deletion referred to, never the bare numeric ID. */
    private void rememberConfirmedDeletions(Set<Long> deletedIds, Map<Long, Song> previous) {        for (Long id : deletedIds) {
            Song song = previous.get(id);
            if (song != null) confirmedDeletedSources.add(sourceKey(song));
        }
    }

    private static String sourceKey(Song song) {
        return io.github.omblivion.needle.model.SongSource.canonicalPath(song.data) + "\u0000" + song.id;
    }

    public Set<Long> getConfirmedDeletedIds() {
        synchronized (cache) {
            Set<Long> ids = new HashSet<>();
            for (Song song : cache.songsById.values()) {
                if (confirmedDeletedSources.contains(sourceKey(song))) ids.add(song.id);
            }
            return ids;
        }
    }

    public boolean isConfirmedDeleted(Song song) {
        synchronized (cache) {
            return confirmedDeletedSources.contains(sourceKey(song));
        }
    }

    public Song resolveReference(Song source) {
        synchronized (cache) {
            // The canonical path is the identity: find the catalog entry at this
            // location and adopt its current provider ID.
            String path = io.github.omblivion.needle.model.SongSource.canonicalPath(source.data);
            for (Song song : cache.songsById.values()) {
                if (io.github.omblivion.needle.model.SongSource.canonicalPath(song.data).equals(path)) {
                    return new Song(source, song.id);
                }
            }
            return confirmedDeletedSources.contains(sourceKey(source)) ? Song.EMPTY_SONG : source;
        }
    }

    // TODO This is not a singleton and should not be declared as such
    @NonNull
    public static Discography getInstance() {
        return App.getDiscography();
    }

    public void addActivity(@NonNull final AbsMusicServiceActivity activity) {
        if (!attachedActivitiesAndQueue.containsKey(activity)) {
            attachedActivitiesAndQueue.put(activity, new Handler(activity.getMainLooper()));

            activity.addMusicServiceEventListener(this);

            if (cache.consistencyState == MemCache.ConsistencyState.UNINITIALIZED) {
                // These operations are IO intensive and may take time, depending on the library size
                // delay it till UI is availble
                triggerSyncWithMediaStore(false);
            }
        }
    }

    public void removeActivity(@NonNull final AbsMusicServiceActivity activity) {
        if (attachedActivitiesAndQueue.containsKey(activity)) {
            activity.removeMusicServiceEventListener(this);

            // Flush the delayed task queue - dont do anything is we are stopping
            final Handler handler = attachedActivitiesAndQueue.get(activity);
            Objects.requireNonNull(handler);
            handler.removeCallbacksAndMessages(TASK_QUEUE_COALESCENCE_TOKEN);

            attachedActivitiesAndQueue.remove(activity);
        }
    }

    void setCacheState(final MemCache.ConsistencyState value) {
        synchronized (cache) {
            cache.consistencyState = value;
        }
    }

    private MemCache.ConsistencyState getCacheState() {
        synchronized (cache) {
            return cache.consistencyState;
        }
    }

    @NonNull
    private Song getOrAddSong(@NonNull final Song song, @NonNull final SyncWithMediaStoreAsyncTask.Progress progress) {
        synchronized (cache) {
            final Song discogSong = getSong(song.id);
            boolean existsAndObsolete = false;
            if (!discogSong.equals(Song.EMPTY_SONG)) {
                final BiPredicate<Song, Song> isMetadataObsolete = (final @NonNull Song incomingSong, final @NonNull Song cachedSong) -> {
                    if (incomingSong.dateAdded != cachedSong.dateAdded) return true;
                    if (incomingSong.dateModified != cachedSong.dateModified) return true;
                    return (!incomingSong.data.equals(cachedSong.data));
                };

                existsAndObsolete = isMetadataObsolete.test(song, discogSong);
                if (existsAndObsolete) {
                    removeSongById(song.id);
                } else {
                    return discogSong;
                }
            }

            addSong(song, false);
            if (existsAndObsolete) {++ progress.updated;}
            else {++ progress.added;}

            return song;
        }
    }

    @NonNull
    public Song getSong(long songId) {
        synchronized (cache) {
            Song song = cache.songsById.get(songId);
            return song == null ? Song.EMPTY_SONG : song;
        }
    }

    @NonNull
    public ArrayList<Song> getSongsFromIdsAndCleanupOrphans(@NonNull ArrayList<Long> songIds, @Nullable Consumer<ArrayList<Long>> orphanIdsCleaner) {
        ArrayList<Long> orphanSongIds = new ArrayList<>();
        ArrayList<Song> songs = new ArrayList<>();

        synchronized (cache) {
            for (Long id : songIds) {
                Song song = cache.songsById.get(id);
                if (song != null) {
                    songs.add(song);
                } else if (orphanIdsCleaner != null) {
                    orphanSongIds.add(id);
                }
            }
        }

        MemCache.ConsistencyState cacheState = getCacheState();
        // In the case where the Discog is being reset situation, the operation takes time,
        // and while the cache is being filled up,
        // correct and existing songs may be considered as orphan
        // --> incorrectly cleaned from the auxiliary DBs (history, queue, etc)
        // Missing from a read snapshot is not proof of deletion. Explicit reconciliation
        // owns persistent cleanup, including history and playlist references.
        return songs;
    }

    @NonNull
    public Song getSongByPath(@NonNull final String path) {
        synchronized (cache) {
            Song matchingSong = Song.EMPTY_SONG;

            for (Song song : cache.songsById.values()) {
                if (song.data.equals(path)) {
                    matchingSong = song;
                    break;
                }
            }
            return matchingSong;
        }
    }

    @NonNull
    public ArrayList<Song> getAllSongs(@Nullable Comparator<Song> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Song> copy = new ArrayList<>(cache.songsById.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            if (sortOrder != null) {Collections.sort(copy, sortOrder);}

            return copy;
        }
    }

    @NonNull
    public Map<Long, Song> getAllSongsById() {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            return new HashMap<>(cache.songsById);
        }
    }

    @Nullable
    public Artist getArtist(long artistId) {
        synchronized (cache) {
            return cache.artistsById.get(artistId);
        }
    }

    @Nullable
    public Artist getArtistByName(String artistName) {
        synchronized (cache) {
            return cache.artistsByName.get(artistName);
        }
    }

    @NonNull
    public ArrayList<Artist> getAllArtists(@NonNull Comparator<Artist> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Artist> copy = new ArrayList<>(cache.artistsById.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);
            return copy;
        }
    }

    @Nullable
    public Album getAlbum(long albumId) {
        synchronized (cache) {
            Map<Long, MemCache.AlbumSlice> albumsByArtist = cache.albumsByAlbumIdAndArtistId.get(albumId);
            if (albumsByArtist == null) return null;
            return mergeFullAlbum(albumsByArtist.values());
        }
    }

    @NonNull
    public ArrayList<Album> getAllAlbums(@NonNull Comparator<? super Album> sortOrder) {
        synchronized (cache) {
            ArrayList<Album> fullAlbums = new ArrayList<>();
            for (Map<Long, MemCache.AlbumSlice> albumsByArtist : cache.albumsByAlbumIdAndArtistId.values()) {
                fullAlbums.add(mergeFullAlbum(albumsByArtist.values()));
            }

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(fullAlbums, sortOrder);

            return fullAlbums;
        }
    }

    @NonNull
    private static Album mergeFullAlbum(@NonNull Iterable<? extends MemCache.AlbumSlice> albumParts) {
        Album fullAlbum = new Album();
        for (Album fragment : albumParts) {
            for (Song song : fragment.songs) {
                if (fullAlbum.songs.contains(song)) continue;
                fullAlbum.songs.add(song);
            }
        }
        // Maintain sorted album after merge
        Collections.sort(fullAlbum.songs, SongSortOrder.BY_DISC_TRACK);
        return fullAlbum;
    }

    @NonNull
    public ArrayList<Genre> getAllGenres(@NonNull Comparator<? super Genre> sortOrder) {
        synchronized (cache) {
            // Make a copy here, to avoid error while the caller is iterating on the result
            ArrayList<Genre> copy = new ArrayList<>(cache.genresByName.values());

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);

            return copy;
        }
    }

    @Nullable
    public ArrayList<Song> getSongsForGenre(long genreId, @NonNull Comparator<? super Song> sortOrder) {
        synchronized (cache) {
            ArrayList<Song> songs = cache.songsByGenreId.get(genreId);
            if (songs == null) {return null;}

            ArrayList<Song> copy = new ArrayList<>(songs);

            // Perform sort inside the critical section, to avoid data race
            // (artist or album being modified while sorting)
            Collections.sort(copy, sortOrder);

            return copy;
        }
    }

    public float getMaxReplayGain() {
        return cache.getMaxReplayGain();
    }

    private void addSong(@NonNull Song song, boolean cacheOnly) {
        synchronized (cache) {
            // Race condition check: If the song has been added -> skip
            if (cache.songsById.containsKey(song.id)) {
                return;
            }

            if (!cacheOnly) {
                TagExtractor.extractTags(song);
            }

            Consumer<List<String>> normNames = (@NonNull List<String> names) -> {
                List<String> normalized = new ArrayList<>();
                for (String name : names) {
                    normalized.add(StringUtil.unicodeNormalize(name));
                }
                names.clear(); names.addAll(normalized);
            };
            normNames.accept(song.albumArtistNames);
            normNames.accept(song.artistNames);
            song.albumName = StringUtil.unicodeNormalize(song.albumName);
            song.title = StringUtil.unicodeNormalize(song.title);
            normNames.accept(song.genres);

            // Replace genre numerical ID3v1 values by textual ones
            List<String> normalizedGenres = new ArrayList<>(song.genres.size());
            for (String genre : song.genres) {
                try {
                    int genreId = Integer.parseInt(genre);
                    String genreName = GenreTypes.getInstanceOf().getValueForId(genreId);
                    if (genreName != null) {
                        normalizedGenres.add(genreName);
                    }
                } catch (NumberFormatException ignored) {
                    normalizedGenres.add(genre);
                }
            }
            song.genres = normalizedGenres;

            cache.addSong(song);

            if (!cacheOnly) {
                database.addSong(song);
            }

            notifyDiscographyChanged();
        }
    }

    public void triggerSyncWithMediaStore(boolean reset) {
        getScanCoordinator().requestScan(new LibraryScanCoordinator.ScanRequest(reset, List.of()));
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(@NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater) {
        return syncWithMediaStore(progressUpdater, false);
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(@NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater, boolean reset) {
        return syncWithMediaStore(progressUpdater, reset, null, false);
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(
            @NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater,
            boolean reset, @Nullable LibraryFileDiscovery.DiscoveryResult discovery,
            boolean complete) {
        final Context context = App.getInstance().getApplicationContext();
        // A failed query must never be mistaken for an empty library, even on reset.
        final ArrayList<Song> alienSongs;
        try {
            alienSongs = MediaStoreBridge.getAllSongs(context);
        } catch (RuntimeException failure) {
            removalTracker.observe(Set.of(), false, 0);
            throw failure;
        }
        long baseRevision = getRevision();
        Map<Long, Song> previous = getAllSongsById();
        ArrayList<Song> candidate = new ArrayList<>();

        // Zombies are tracks that are removed but still indexed by MediaStore
        final Predicate<Song> isZombie = (s) -> !(new File(s.data)).exists();

        // Whitelist
        final File startDirectory = PreferenceUtil.getInstance().getStartDirectory();
        final String startPath = FileUtil.safeGetCanonicalPath(startDirectory);
        final Predicate<Song> isNotWhiteListed = (s) -> {
            if (PreferenceUtil.getInstance().getWhitelistEnabled()) {
                return !isInDirectory(s.data, startPath);
            }
            return false;
        };

        // Blacklist
        final ArrayList<String> blackListedPaths = BlacklistStore.getInstance(context).getPaths();
        final Predicate<Song> isBlackListed = (s) -> {
            for (final String path : blackListedPaths) {
                if (isInDirectory(s.data, path)) return true;
            }
            return false;
        };

        final SyncWithMediaStoreAsyncTask.Progress counters = new SyncWithMediaStoreAsyncTask.Progress();
        final Set<Long> importedSongIds = new HashSet<>();
        final Set<Long> providerIds = new HashSet<>();
        final Set<String> providerPaths = new HashSet<>();
        for (Song song : alienSongs) {
            providerIds.add(song.id);
            providerPaths.add(io.github.omblivion.needle.model.SongSource.canonicalPath(song.data));
        }
        for (final Song song : alienSongs) {
            if (isNotWhiteListed.test(song)) continue;
            if (isBlackListed.test(song)) continue;
            if (isZombie.test(song)) continue;

            Song existing = previous.get(song.id);
            Song matchedSong;
            String versionKey = song.contentUri + "|" + song.data + "|" + song.sourceSize
                    + "|" + song.sourceGeneration + "|" + song.dateModified + "|" + song.providerVersion;
            int attempts = reset ? 0 : metadataAttempts.getOrDefault(versionKey, 0);
            if (!reset && existing != null && (!existing.metadataRetryNeeded || attempts >= 3)
                    && !io.github.omblivion.needle.model.SongSource.from(song)
                    .versionChanged(io.github.omblivion.needle.model.SongSource.from(existing))) {
                matchedSong = new Song(existing);
            } else {
                matchedSong = new Song(song);
                MetadataReadResult metadata = TagExtractor.readTags(matchedSong);
                matchedSong = metadata.song;
                if (matchedSong.metadataRetryNeeded) metadataAttempts.put(versionKey, attempts + 1);
                else metadataAttempts.remove(versionKey);
                normalizeSong(matchedSong);
                if (existing == null) counters.added++;
                else counters.updated++;
            }
            candidate.add(matchedSong);
            importedSongIds.add(matchedSong.id);

            progressUpdater.accept(counters);
        }

        Set<Long> absent = new HashSet<>();
        boolean healthy = complete && discovery != null && discovery.complete;
        for (Song old : previous.values()) {
            if (healthy && discovery.covers(old.data) && !providerIds.contains(old.id)
                    && !providerPaths.contains(io.github.omblivion.needle.model.SongSource.canonicalPath(old.data))
                    && !discovery.files.containsKey(io.github.omblivion.needle.model.SongSource.canonicalPath(old.data))
                    && !new File(old.data).exists() && !isNotWhiteListed.test(old)
                    && !isBlackListed.test(old)) absent.add(old.id);
        }
        Set<Long> removed = removalTracker.observe(absent, healthy, android.os.SystemClock.elapsedRealtime());
        Set<String> removedPaths = new HashSet<>();
        for (Long id : removed) {
            Song gone = previous.get(id);
            if (gone != null) {
                removedPaths.add(io.github.omblivion.needle.model.SongSource.canonicalPath(gone.data));
            }
        }
        for (Song old : previous.values()) {
            if (importedSongIds.contains(old.id) || removed.contains(old.id)) continue;
            if (isNotWhiteListed.test(old) || isBlackListed.test(old)) continue;
            if (confirmedDeletedSources.contains(sourceKey(old))) continue;
            if (providerPaths.contains(io.github.omblivion.needle.model.SongSource.canonicalPath(old.data))
                    && !providerIds.contains(old.id)) {
                // Provider still lists this path under a changed ID that was not imported
                // (for example the file is temporarily inaccessible): keep the last entry.
            } else if (providerPaths.contains(io.github.omblivion.needle.model.SongSource.canonicalPath(old.data))) {
                continue;
            }
            candidate.add(new Song(old));
        }
        counters.removed = removed.size();
        publishSnapshot(new LibrarySnapshot(candidate, baseRevision, removed, removedPaths));

        return counters;
    }

    boolean hasMetadataRetries() {
        for (int attempts : metadataAttempts.values()) if (attempts > 0 && attempts < 3) return true;
        return false;
    }

    private static void normalizeSong(Song song) {
        song.title = StringUtil.unicodeNormalize(song.title);
        song.albumName = StringUtil.unicodeNormalize(song.albumName);
        song.artistNames = normalizeNames(song.artistNames);
        song.albumArtistNames = normalizeNames(song.albumArtistNames);
        ArrayList<String> genres = new ArrayList<>();
        for (String genre : song.genres) {
            try {
                String name = GenreTypes.getInstanceOf().getValueForId(Integer.parseInt(genre));
                if (name != null) genres.add(name);
            } catch (NumberFormatException textualGenre) {
                genres.add(StringUtil.unicodeNormalize(genre));
            }
        }
        song.genres = genres;
    }

    private static List<String> normalizeNames(List<String> names) {
        ArrayList<String> normalized = new ArrayList<>();
        for (String name : names) normalized.add(StringUtil.unicodeNormalize(name));
        return normalized;
    }

    private static boolean isInDirectory(String filePath, String directoryPath) {
        String directory = FileUtil.safeGetCanonicalPath(new File(directoryPath));
        String file = FileUtil.safeGetCanonicalPath(new File(filePath));
        return file.equals(directory) || file.startsWith(directory.endsWith(File.separator)
                ? directory : directory + File.separator);
    }

    @Override
    public void onServiceConnected() {}

    @Override
    public void onServiceDisconnected() {}

    @Override
    public void onQueueChanged() {}

    @Override
    public void onPlayingMetaChanged() {}

    @Override
    public void onPlayStateChanged() {}

    @Override
    public void onRepeatModeChanged() {}

    @Override
    public void onShuffleModeChanged() {}

    @Override
    public void onMediaStoreChanged() {
        triggerSyncWithMediaStore(false);
    }

    public void addChangedListener(Runnable listener) {
        changedListeners.put(listener, new Handler(android.os.Looper.getMainLooper()));
    }

    public void removeChangedListener(Runnable listener) {
        Handler handler = changedListeners.remove(listener);
        if (handler != null) handler.removeCallbacks(listener);
    }

    private void notifyDiscographyChanged() {
        // Notify the attached activiies to reload the UI content
        // Since this can be called from a background thread, make it safe by wrapping as an event to main thread
        changedListeners.forEach((listener, handler) -> {
            handler.removeCallbacks(listener);
            handler.postDelayed(listener, 200L);
        });
    }

    public void removeSongByPath(@NonNull String... paths) {
        synchronized (cache) {
            for (String path : paths) {
                for (Song song : cache.songsById.values()) {
                    if (song.data.equals(path)) {
                        // Tag edits invalidate derived metadata, not logical membership.
                        song.metadataRetryNeeded = true;
                    }
                }
            }
        }
    }

    public void confirmDeletion(@NonNull Collection<Song> songs) {
        ReferenceReconciliationJournal.access(() -> {
            synchronized (cache) {
                Map<Long, Song> remaining = new HashMap<>(cache.songsById);
                Set<Long> deleted = new HashSet<>();
                Set<String> deletedPaths = new HashSet<>();
                for (Song song : songs) {
                    Song current = remaining.get(song.id);
                    if (current != null && io.github.omblivion.needle.model.SongSource
                            .canonicalPath(current.data).equals(io.github.omblivion.needle.model.SongSource
                                    .canonicalPath(song.data))) {
                        remaining.remove(song.id);
                        deleted.add(song.id);
                        deletedPaths.add(io.github.omblivion.needle.model.SongSource
                                .canonicalPath(song.data));
                    }
                }
                rememberConfirmedDeletions(deleted, cache.songsById);
                publishSnapshot(new LibrarySnapshot(remaining.values(), revision, deleted, deletedPaths));
            }
            return null;
        });
    }

    private void removeSongById(final long songId) {
        removeSongsById(List.of(songId));
    }

    private void removeSongsById(@NonNull final Collection<Long> songIds) {
        if (songIds.isEmpty()) return;

        for (final long songId : songIds) {
            cache.removeSongById(songId);
            database.removeSongById(songId);
        }
        notifyDiscographyChanged();
    }

    void clear() {
        database.clear();
        cache.clear();
    }

    private void fetchAllSongs() {
        setCacheState(MemCache.ConsistencyState.REFRESHING);

        Collection<Song> songs = database.fetchAllSongs();
        for (Song song : songs) {
            addSong(song, true);
        }

        setCacheState(MemCache.ConsistencyState.OK);
    }
}
