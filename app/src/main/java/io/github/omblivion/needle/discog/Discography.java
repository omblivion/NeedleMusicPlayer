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
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.provider.BlacklistStore;
import io.github.omblivion.needle.provider.LibraryReferenceStores;
import io.github.omblivion.needle.sort.SongSortOrder;
import io.github.omblivion.needle.ui.activities.base.AbsMusicServiceActivity;
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
    private volatile Map<String, Song> songsByCanonicalPath = Map.of();
    private long revision;
    private final Object coordinatorLock = new Object();
    private LibraryScanCoordinator scanCoordinator;
    private final LibraryRemovalTracker removalTracker = new LibraryRemovalTracker();
    private ReferenceReconciliationJournal referenceJournal;
    private boolean journalRecovered;
    private final Map<String, Integer> metadataAttempts = new HashMap<>();
    private final Set<String> confirmedDeletedSources = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            database.applyDelta(database.changes(previous, pending.snapshot.songs.values()));
            Set<String> deletions = confirmedDeletionKeys(pending.snapshot.confirmedDeleted, previous);
            synchronized (cache) {
                cache.replaceWith(pending.snapshot.prepared);
                songsByCanonicalPath = pending.snapshot.songsByPath;
                cache.consistencyState = MemCache.ConsistencyState.OK;
                confirmedDeletedSources.addAll(deletions);
                revision++;
            }
            referenceJournal.complete();
        }
        journalRecovered = true;
        notifyDiscographyChanged();
    }

    boolean hasPendingRemovals() { return removalTracker.hasPending(); }

    public LibraryScanCoordinator getScanCoordinator() {
        synchronized (coordinatorLock) {
            if (scanCoordinator == null) {
                scanCoordinator = LibraryScanCoordinator.create(App.getStaticContext(), this);
            }
            return scanCoordinator;
        }
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
        publishSnapshot(snapshot, null);
    }

    private void publishSnapshot(@NonNull LibrarySnapshot snapshot, @Nullable DB.Delta delta) {
        publishSnapshot(snapshot, delta, () -> true);
    }

    private boolean publishSnapshot(LibrarySnapshot snapshot, @Nullable DB.Delta delta,
            java.util.function.BooleanSupplier scopeIsCurrent) {
        Map<Long, Song> beforePublication = getAllSongsById();
        ReferencePlan plan = LibraryReferenceReconciler.deriveByPath(
                referenceKeysFromCatalog(beforePublication), snapshot.songsByPath,
                snapshot.confirmedDeletedPaths);
        Set<String> deletions = confirmedDeletionKeys(snapshot.confirmedDeleted, beforePublication);
        boolean published = ReferenceReconciliationJournal.access(() -> {
            if (!scopeIsCurrent.getAsBoolean()) return false;
            if (snapshot.baseRevision != getRevision()) {
                throw new IllegalStateException("Obsolete library snapshot");
            }
            String operation = null;
            if (!plan.isEmpty()) {
                if (referenceJournal == null) referenceJournal = new ReferenceReconciliationJournal();
                operation = referenceJournal.begin(snapshot);
                journalRecovered = false;
            }
            // Database and reference writes may block; readers keep using the
            // previous catalog until the complete publication is durable.
            if (operation != null) {
                ReferenceReconciliationJournal.applyGate.beforeApply();
                LibraryReferenceStores.apply(operation, plan, snapshot.songs);
            }
            if (delta == null) database.replaceSongs(snapshot.songs.values());
            else database.applyDelta(delta);
            if (operation != null) {
                referenceJournal.complete();
                journalRecovered = true;
            }
            synchronized (cache) {
                cache.replaceWith(snapshot.prepared);
                songsByCanonicalPath = snapshot.songsByPath;
                confirmedDeletedSources.addAll(deletions);
                cache.consistencyState = MemCache.ConsistencyState.OK;
                revision++;
            }
            return true;
        });
        if (published) notifyDiscographyChanged();
        return published;
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
    private static Set<String> confirmedDeletionKeys(Set<Long> deletedIds, Map<Long, Song> previous) {
        Set<String> keys = new HashSet<>();
        for (Long id : deletedIds) {
            Song song = previous.get(id);
            if (song != null) keys.add(sourceKey(song));
        }
        return keys;
    }

    private static String sourceKey(Song song) {
        return io.github.omblivion.needle.model.SongSource.canonicalPath(song.data) + "\u0000" + song.id;
    }

    public Set<Long> getConfirmedDeletedIds() {
        if (confirmedDeletedSources.isEmpty()) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (Song song : getAllSongs(null)) {
            if (confirmedDeletedSources.contains(sourceKey(song))) ids.add(song.id);
        }
        return ids;
    }

    public boolean isConfirmedDeleted(Song song) {
        String path = song.getCanonicalCatalogPath();
        return !songsByCanonicalPath.containsKey(path)
                && confirmedDeletedSources.contains(path + "\u0000" + song.id);
    }

    public Song resolveReference(Song source) {
        String path = source.getCanonicalCatalogPath();
        Song match = songsByCanonicalPath.get(path);
        if (match != null) return new Song(source, match.id);
        return confirmedDeletedSources.contains(path + "\u0000" + source.id) ? Song.EMPTY_SONG : source;
    }

    /** Immutable path index of one published catalog, safe to read during a scan. */
    public Map<String, Song> getSongsByCanonicalPath() {
        return songsByCanonicalPath;
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
        return songsByCanonicalPath.getOrDefault(SongSource.canonicalPath(path), Song.EMPTY_SONG);
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
        String path = SongSource.canonicalPath(song.data);
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
            if (!path.isEmpty()) {
                Map<String, Song> updated = new HashMap<>(songsByCanonicalPath);
                updated.putIfAbsent(path, song);
                songsByCanonicalPath = Map.copyOf(updated);
            }

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
        return syncWithMediaStore(progressUpdater, reset, discovery, complete, false);
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(
            @NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater,
            boolean forceMetadata, @Nullable LibraryFileDiscovery.DiscoveryResult discovery,
            boolean complete, boolean targeted) {
        return syncWithMediaStore(progressUpdater, forceMetadata, discovery, complete, targeted, () -> true);
    }

    SyncWithMediaStoreAsyncTask.Progress syncWithMediaStore(
            @NonNull final Consumer<SyncWithMediaStoreAsyncTask.Progress> progressUpdater,
            boolean forceMetadata, @Nullable LibraryFileDiscovery.DiscoveryResult discovery,
            boolean complete, boolean targeted, java.util.function.BooleanSupplier scopeIsCurrent) {
        final Context context = App.getInstance().getApplicationContext();
        final SyncWithMediaStoreAsyncTask.Progress counters = new SyncWithMediaStoreAsyncTask.Progress();
        boolean rebuild = forceMetadata && !targeted;
        boolean healthy = complete && discovery != null && discovery.complete;
        if (forceMetadata && discovery != null && !healthy) {
            removalTracker.observe(Set.of(), false, 0);
            counters.deferred = true;
            return counters;
        }
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
        boolean indexedPreview = discovery != null && !complete && !targeted
                && !forceMetadata && previous.isEmpty();
        ArrayList<Song> candidate = new ArrayList<>();

        final String selectedRoot = targeted ? SongSource.canonicalPath(
                PreferenceUtil.getInstance().getStartDirectory().getPath()) : null;
        final Predicate<String> inScope = path -> (discovery == null || discovery.coversCanonicalPath(path))
                && (selectedRoot == null || LibraryFileDiscovery.containsCanonicalPath(path, selectedRoot));
        final List<String> blackListedPaths = BlacklistStore.getInstance(context).getPaths().stream()
                .map(SongSource::canonicalPath).toList();
        final Predicate<String> isBlackListed = path -> {
            for (String excluded : blackListedPaths) {
                if (LibraryFileDiscovery.containsCanonicalPath(path, excluded)) return true;
            }
            return false;
        };

        final Set<Long> importedSongIds = new HashSet<>();
        final Set<String> importedPaths = new HashSet<>();
        final Set<Long> providerIds = new HashSet<>();
        final Set<String> providerPaths = new HashSet<>();
        for (final Song song : alienSongs) {
            String path = SongSource.canonicalPath(song.data);
            providerIds.add(song.id);
            providerPaths.add(path);
            if (!inScope.test(path) || isBlackListed.test(path)) continue;
            if (discovery != null ? !discovery.files.containsKey(path) : !new File(song.data).isFile()) continue;
            if (importedPaths.contains(path) || importedSongIds.contains(song.id)) continue;

            Song existing = previous.get(song.id);
            if (targeted && existing != null
                    && !inScope.test(existing.getCanonicalCatalogPath())) continue;
            Song matchedSong;
            String versionKey = song.contentUri + "|" + song.data + "|" + song.sourceSize
                    + "|" + song.sourceGeneration + "|" + song.dateModified + "|" + song.providerVersion;
            int attempts = forceMetadata ? 0 : metadataAttempts.getOrDefault(versionKey, 0);
            if (indexedPreview) {
                // The provider metadata is enough to show and play an indexed
                // track. Read tags in the later pass, not on the startup path.
                matchedSong = new Song(song);
                matchedSong.metadataRetryNeeded = true;
                normalizeSong(matchedSong);
                counters.added++;
            } else if (!forceMetadata && existing != null && (!existing.metadataRetryNeeded || attempts >= 3)
                    && existing.data.equals(song.data) && existing.contentUri.equals(song.contentUri)
                    && existing.dateModified == song.dateModified
                    && !SongSource.from(existing).versionChanged(SongSource.from(song))) {
                matchedSong = existing;
            } else {
                matchedSong = new Song(song);
                MetadataReadResult metadata = TagExtractor.readTags(matchedSong);
                Song oldByPath = songsByCanonicalPath.get(path);
                if (metadata.song.metadataRetryNeeded && oldByPath != null && !oldByPath.metadataRetryNeeded) {
                    if (forceMetadata) {
                        counters.deferred = true;
                        return counters;
                    }
                    metadataAttempts.put(versionKey, attempts + 1);
                    // Keep the last complete metadata; an unstable file is not a new catalog version.
                    continue;
                }
                matchedSong = metadata.song;
                if (matchedSong.metadataRetryNeeded) metadataAttempts.put(versionKey, attempts + 1);
                else metadataAttempts.remove(versionKey);
                normalizeSong(matchedSong);
                if (existing == null) counters.added++;
                else counters.updated++;
            }
            candidate.add(matchedSong);
            importedSongIds.add(matchedSong.id);
            importedPaths.add(path);

            progressUpdater.accept(counters);
        }

        if (forceMetadata && discovery != null && !importedPaths.containsAll(discovery.files.keySet())) {
            removalTracker.observe(Set.of(), false, 0);
            counters.deferred = true;
            return counters;
        }

        Set<Long> absent = new HashSet<>();
        for (Song old : previous.values()) {
            String path = SongSource.canonicalPath(old.data);
            if (healthy && inScope.test(path) && !providerIds.contains(old.id)
                    && !providerPaths.contains(path) && !discovery.files.containsKey(path)
                    && !new File(old.data).exists() && !isBlackListed.test(path)) absent.add(old.id);
        }
        Set<Long> removed = removalTracker.observe(absent, healthy, android.os.SystemClock.elapsedRealtime());
        for (Song old : previous.values()) {
            String path = old.getCanonicalCatalogPath();
            if (targeted && !inScope.test(path)) {
                candidate.add(old);
                continue;
            }
            if (importedSongIds.contains(old.id) || removed.contains(old.id)) continue;
            if (importedPaths.contains(path) || isBlackListed.test(path)) continue;
            if (healthy && !targeted && !inScope.test(path)) continue;
            if (confirmedDeletedSources.contains(path + "\u0000" + old.id)) continue;
            candidate.add(old);
        }
        counters.removed = removed.size();
        DB.Delta delta = rebuild ? null : database.changes(previous, candidate);
        if (delta == null || !delta.isEmpty()) {
            // Absence may evict a catalog row, never its playlists, history or statistics.
            counters.deferred = !publishSnapshot(new LibrarySnapshot(candidate, baseRevision), delta,
                    scopeIsCurrent);
        }

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
                Map<Long, Song> remaining = getAllSongsById();
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
                if (!deleted.isEmpty()) {
                    DB.Delta delta = new DB.Delta(deleted, List.of());
                    publishSnapshot(new LibrarySnapshot(remaining.values(), getRevision(), deleted, deletedPaths), delta);
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
        songsByCanonicalPath = Map.of();
    }

    private void fetchAllSongs() {
        setCacheState(MemCache.ConsistencyState.REFRESHING);

        Collection<Song> songs = database.fetchAllSongs();
        for (Song song : songs) normalizeSong(song);
        LibrarySnapshot loaded = new LibrarySnapshot(songs, getRevision());
        synchronized (cache) {
            cache.replaceWith(loaded.prepared);
            songsByCanonicalPath = loaded.songsByPath;
            cache.consistencyState = MemCache.ConsistencyState.OK;
        }
        notifyDiscographyChanged();
    }
}
