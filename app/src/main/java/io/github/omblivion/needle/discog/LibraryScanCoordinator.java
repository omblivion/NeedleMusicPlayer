package io.github.omblivion.needle.discog;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.omblivion.needle.provider.BlacklistStore;
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
            this.roots = new ArrayList<>(roots);
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
    private ScanRequest pending;
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
            pending = pending == null ? request : pending.merge(request);
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
        if (pending != null) {
            ScanRequest next = pending;
            pending = null;
            requestScan(next);
        }
    }

    private static final class AndroidBackend implements Backend {
        final Context context;
        final Discography discography;
        final Handler handler;
        final Runnable rescan;
        final Map<String, LibraryFileDiscovery.Observation> previous = new HashMap<>();
        final List<FileObserver> observers = new ArrayList<>();
        LibraryFileDiscovery.DiscoveryResult discovery;

        AndroidBackend(Context context, Discography discography, Handler handler) {
            this.context = context.getApplicationContext();
            this.discography = discography;
            this.handler = handler;
            rescan = () -> discography.triggerSyncWithMediaStore(false);
        }

        @Override public List<String> discover(ScanRequest request) {
            discography.ensureLoaded();
            List<File> roots = request.roots;
            if (roots.isEmpty()) roots = List.of(PreferenceUtil.getInstance().getWhitelistEnabled()
                    ? PreferenceUtil.getInstance().getStartDirectory()
                    : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
            discovery = new LibraryFileDiscovery(BlacklistStore.getInstance(context).getPaths()).discover(roots);
            List<String> paths = new ArrayList<>();
            Set<String> catalogPaths = new HashSet<>();
            for (io.github.omblivion.needle.model.Song song : discography.getAllSongs(null)) {
                catalogPaths.add(io.github.omblivion.needle.model.SongSource.canonicalPath(song.data));
            }
            for (Map.Entry<String, LibraryFileDiscovery.Observation> entry : discovery.files.entrySet()) {
                if (request.forceRebuild || !catalogPaths.contains(entry.getKey())
                        || !entry.getValue().sameVersion(previous.get(entry.getKey()))) paths.add(entry.getKey());
            }
            for (FileObserver observer : observers) observer.stopWatching();
            observers.clear();
            for (File directory : discovery.directories) {
                FileObserver observer = new FileObserver(directory,
                        FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                                | FileObserver.DELETE | FileObserver.CREATE | FileObserver.DELETE_SELF) {
                    @Override public void onEvent(int event, String path) {
                        handler.removeCallbacks(rescan);
                        handler.postDelayed(rescan, 500);
                    }
                };
                observer.startWatching();
                observers.add(observer);
            }
            return paths;
        }

        @Override public void index(List<String> paths, IndexCallback callback) {
            MediaScannerConnection.scanFile(context, paths.toArray(new String[0]), null,
                    (path, uri) -> callback.complete(path, uri != null));
        }
        @Override public void reconcile(ScanRequest request, boolean complete) {
            SyncWithMediaStoreAsyncTask.Progress progress =
                    discography.syncWithMediaStore(p -> {}, request.forceRebuild, discovery, complete);
            if (request.forceRebuild) {
                boolean healthy = complete && discovery.complete;
                String info = progress.buildInfoString(context);
                handler.post(() -> io.github.omblivion.needle.util.SafeToast.show(context,
                        healthy ? context.getString(io.github.omblivion.needle.R.string.library_scan_completed)
                                + (info.isEmpty() ? "" : ": " + info)
                                : context.getString(io.github.omblivion.needle.R.string.library_scan_deferred)));
            }
            if (complete && discovery.complete) {
                previous.clear();
                previous.putAll(discovery.files);
            }
            if (discography.hasPendingRemovals() || discography.hasMetadataRetries()) {
                handler.postDelayed(rescan, 2_000);
            }
        }

        @Override public void failed(ScanRequest request) {
            if (request.forceRebuild) handler.post(() ->
                    io.github.omblivion.needle.util.SafeToast.show(context,
                            io.github.omblivion.needle.R.string.library_scan_failed));
        }
    }
}
