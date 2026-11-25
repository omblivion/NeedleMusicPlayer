package io.github.omblivion.needle.discog;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.omblivion.needle.provider.BlacklistStore;
import io.github.omblivion.needle.model.Song;
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.util.PreferenceUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Single-flight pipeline: discover -> index barrier -> reconcile -> one follow-up. */
public final class LibraryScanCoordinator {
    public static final class ScanRequest {
        public final boolean forceRebuild;
        public final List<File> roots;
        public ScanRequest(boolean forceRebuild, List<File> roots) {
            this.forceRebuild = forceRebuild;
            this.roots = List.copyOf(roots);
        }
        ScanRequest merge(ScanRequest other) {
            Set<File> combined = new LinkedHashSet<>(roots);
            combined.addAll(other.roots);
            return new ScanRequest(forceRebuild || other.forceRebuild, new ArrayList<>(combined));
        }
    }
    public interface IndexCallback { void complete(String path, boolean success); }
    interface Scheduler { void later(Runnable runnable, long delayMs); }
    interface Backend {
        List<String> discover(ScanRequest request);
        void index(List<String> paths, IndexCallback callback);
        void reconcile(ScanRequest request, boolean complete);
        default void failed(ScanRequest request) {}
    }

    private final Backend backend;
    private final Executor worker;
    private final Scheduler scheduler;
    private ScanRequest pendingLibrary;
    private ScanRequest pendingTargeted;
    private boolean running;

    LibraryScanCoordinator(Backend backend, Executor worker, Scheduler scheduler) {
        this.backend = backend;
        this.worker = worker;
        this.scheduler = scheduler;
    }

    static LibraryScanCoordinator create(Context context, Discography discography) {
        Handler handler = new Handler(Looper.getMainLooper());
        return new LibraryScanCoordinator(new AndroidBackend(context, discography, handler),
                Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "NeedleLibraryScan");
                    thread.setDaemon(true);
                    return thread;
                }), (r, delay) -> handler.postDelayed(r, delay));
    }

    public synchronized void requestScan(ScanRequest request) {
        if (running) {
            if (request.roots.isEmpty()) {
                pendingLibrary = pendingLibrary == null ? request : pendingLibrary.merge(request);
            } else {
                pendingTargeted = pendingTargeted == null ? request : pendingTargeted.merge(request);
            }
            return;
        }
        running = true;
        worker.execute(() -> start(request));
    }

    private void start(ScanRequest request) {
        try {
            List<String> paths = backend.discover(request);
            indexBatch(request, paths, 0, true);
        } catch (RuntimeException failed) {
            Log.w("LibraryScan", "Discovery failed; previous catalog retained", failed);
            backend.failed(request);
            finish();
        }
    }

    private void indexBatch(ScanRequest request, List<String> paths, int offset, boolean complete) {
        if (offset >= paths.size()) {
            try {
                backend.reconcile(request, complete);
            } catch (RuntimeException failed) {
                Log.w("LibraryScan", "Reconciliation failed; previous catalog retained", failed);
                backend.failed(request);
            } finally {
                finish();
            }
            return;
        }
        List<String> batch = paths.subList(offset, Math.min(offset + 100, paths.size()));
        Set<String> awaiting = new HashSet<>(batch);
        Object lock = new Object();
        boolean[] finished = {false};
        boolean[] successful = {complete};
        Runnable timeout = () -> {
            synchronized (lock) {
                if (finished[0]) return;
                finished[0] = true;
            }
            worker.execute(() -> indexBatch(request, paths, offset + batch.size(), false));
        };
        scheduler.later(timeout, 30_000);
        try {
            backend.index(batch, (path, success) -> {
                boolean proceed;
                boolean healthy;
                synchronized (lock) {
                    if (finished[0] || !awaiting.remove(path)) return;
                    successful[0] &= success;
                    proceed = awaiting.isEmpty();
                    healthy = successful[0];
                    if (proceed) finished[0] = true;
                }
                if (proceed) worker.execute(() -> indexBatch(request, paths,
                        offset + batch.size(), healthy));
            });
        } catch (RuntimeException failed) {
            timeout.run();
        }
    }

    private synchronized void finish() {
        running = false;
        if (pendingLibrary != null) {
            ScanRequest next = pendingLibrary;
            pendingLibrary = null;
            requestScan(next);
        } else if (pendingTargeted != null) {
            ScanRequest next = pendingTargeted;
            pendingTargeted = null;
            requestScan(next);
        }
    }

    private static final class AndroidBackend implements Backend {
        final Context context;
        final Discography discography;
        final Handler handler;
        final Runnable rescan;
        final Map<String, LibraryFileDiscovery.Observation> previous = new HashMap<>();
        final Map<File, FileObserver> observers = new HashMap<>();
        LibraryFileDiscovery.DiscoveryResult discovery;
        String selectedRoot;
        String rootSetting;

        AndroidBackend(Context context, Discography discography, Handler handler) {
            this.context = context.getApplicationContext();
            this.discography = discography;
            this.handler = handler;
            rescan = () -> discography.triggerSyncWithMediaStore(false);
        }

        @Override public List<String> discover(ScanRequest request) {
            discography.ensureLoaded();
            List<File> roots = request.roots;
            PreferenceUtil preferences = PreferenceUtil.getInstance();
            rootSetting = preferences.getLibraryRootSetting();
            File configured = preferences.getStartDirectory();
            selectedRoot = SongSource.canonicalPath(configured.getPath());
            if (roots.isEmpty()) roots = List.of(configured);
            else roots = roots.stream().filter(file -> LibraryFileDiscovery.inDirectory(
                    file.getPath(), selectedRoot)).toList();
            discovery = new LibraryFileDiscovery(BlacklistStore.getInstance(context).getPaths()).discover(roots);
            if (roots.isEmpty()) discovery.complete = false;
            List<String> paths = new ArrayList<>();
            Map<String, Song> catalog = new HashMap<>();
            for (Song song : discography.getAllSongs(null)) {
                catalog.put(SongSource.canonicalPath(song.data), song);
            }
            for (Map.Entry<String, LibraryFileDiscovery.Observation> entry : discovery.files.entrySet()) {
                var last = previous.get(entry.getKey());
                Song existing = catalog.get(entry.getKey());
                if (request.forceRebuild || existing == null
                        || (last == null ? !entry.getValue().matches(existing)
                                         : !entry.getValue().sameVersion(last))) paths.add(entry.getKey());
            }
            if (request.roots.isEmpty() && discovery.complete) updateObservers(discovery.directories);
            return paths;
        }

        private void updateObservers(List<File> directories) {
            Set<File> wanted = new HashSet<>(directories);
            observers.entrySet().removeIf(entry -> {
                if (wanted.contains(entry.getKey())) return false;
                entry.getValue().stopWatching();
                return true;
            });
            for (File directory : directories) observers.computeIfAbsent(directory, dir -> {
                FileObserver observer = new FileObserver(dir,
                        FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                                | FileObserver.DELETE | FileObserver.CREATE | FileObserver.DELETE_SELF) {
                    @Override public void onEvent(int event, String path) {
                        handler.removeCallbacks(rescan);
                        handler.postDelayed(rescan, 500);
                    }
                };
                observer.startWatching();
                return observer;
            });
        }

        @Override public void index(List<String> paths, IndexCallback callback) {
            MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null,
                    (path, uri) -> callback.complete(path, uri != null));
        }
        @Override public void reconcile(ScanRequest request, boolean complete) {
            if (!scopeIsCurrent()) {
                handler.post(rescan);
                return;
            }
            boolean targeted = !request.roots.isEmpty();
            SyncWithMediaStoreAsyncTask.Progress progress =
                    discography.syncWithMediaStore(p -> {}, request.forceRebuild, discovery, complete, targeted,
                            this::scopeIsCurrent);
            if (!scopeIsCurrent()) {
                handler.post(rescan);
                return;
            }
            if (request.forceRebuild) {
                boolean healthy = !progress.deferred && complete && discovery.complete;
                String info = progress.buildInfoString(context);
                handler.post(() -> io.github.omblivion.needle.util.SafeToast.show(context,
                        healthy ? context.getString(io.github.omblivion.needle.R.string.library_scan_completed)
                                + (info.isEmpty() ? "" : ": " + info)
                                : context.getString(io.github.omblivion.needle.R.string.library_scan_deferred)));
            }
            if (!progress.deferred && complete && discovery.complete) {
                if (!targeted) previous.clear();
                previous.putAll(discovery.files);
            }
            if (discography.hasPendingRemovals() || discography.hasMetadataRetries()) {
                handler.postDelayed(rescan, 2_000);
            }
        }

        private boolean scopeIsCurrent() {
            return rootSetting.equals(PreferenceUtil.getInstance().getLibraryRootSetting());
        }

        @Override public void failed(ScanRequest request) {
            if (request.forceRebuild) handler.post(() ->
                    io.github.omblivion.needle.util.SafeToast.show(context,
                            io.github.omblivion.needle.R.string.library_scan_failed));
        }
    }
}
