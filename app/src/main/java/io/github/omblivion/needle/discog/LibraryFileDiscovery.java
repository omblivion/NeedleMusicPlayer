package io.github.omblivion.needle.discog;

import android.webkit.MimeTypeMap;
import io.github.omblivion.needle.model.SongSource;
import io.github.omblivion.needle.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filesystem evidence, including whether enumeration actually succeeded. */
public final class LibraryFileDiscovery {
    public static final class Observation {
        public final File file;
        public final long size;
        public final long modified;
        Observation(File file) {
            this.file = file;
            size = file.length();
            modified = file.lastModified();
        }
        boolean sameVersion(Observation other) {
            return other != null && size == other.size && modified == other.modified;
        }

        /** True when the catalog row still describes this file version. */
        public boolean matches(io.github.omblivion.needle.model.Song song) {
            if (song.sourceSize >= 0 && song.sourceSize != size) return false;
            if (song.sourceSize < 0 && song.dateModified > 0
                    && song.dateModified != modified / 1000) return false;
            return true;
        }
    }

    public static final class DiscoveryResult {
        public final Map<String, Observation> files = new LinkedHashMap<>();
        public final List<File> directories = new ArrayList<>();
        public final List<String> roots = new ArrayList<>();
        public boolean complete = true;

        public boolean covers(String path) {
            for (String root : roots) if (inDirectory(path, root)) return true;
            return false;
        }
    }

    private final List<String> excluded;

    public LibraryFileDiscovery(Collection<String> excluded) {
        this.excluded = new ArrayList<>(excluded);
    }

    public DiscoveryResult discover(Collection<File> roots) {
        DiscoveryResult result = new DiscoveryResult();
        Set<String> visited = new HashSet<>();
        List<String> canonicalRoots = new ArrayList<>();
        for (File root : roots) {
            File canonical = root;
            try {
                canonical = root.getCanonicalFile();
            } catch (IOException | SecurityException inaccessible) {
                result.complete = false;
                continue;
            }
            if (!canonical.isDirectory() || !canonical.canRead()) {
                result.complete = false;
                continue;
            }
            canonicalRoots.add(canonical.getPath());
            visit(canonical, result, visited, canonical.getPath());
        }
        result.roots.addAll(canonicalRoots);
        return result;
    }

    private void visit(File file, DiscoveryResult result, Set<String> visited, String rootPath) {
        try {
            String path = file.getCanonicalPath();
            for (String excludedPath : excluded) if (inDirectory(path, excludedPath)) return;
            if (file.isHidden() || file.getName().startsWith(".")) return;
            // Descendants must stay inside the selected canonical root; this
            // rejects symlink cycles and aliases pointing elsewhere.
            if (!inDirectory(path, rootPath)) return;
            if (!visited.add(path)) return;
            if (file.isDirectory()) {
                if (new File(file, ".nomedia").exists()) return;
                File[] children = file.listFiles();
                if (children == null) {
                    result.complete = false;
                    return;
                }
                result.directories.add(file);
                for (File child : children) visit(child, result, visited, rootPath);
            } else if (file.isFile() && isAudio(file)) {
                result.files.put(path, new Observation(file));
            } else if (!file.exists()) {
                result.complete = false;
            }
        } catch (IOException | SecurityException inaccessible) {
            result.complete = false;
        }
    }

    public static boolean inDirectory(String path, String directory) {
        String file = SongSource.canonicalPath(path);
        String root = SongSource.canonicalPath(directory);
        return file.equals(root) || file.startsWith(root.endsWith(File.separator)
                ? root : root + File.separator);
    }

    private static boolean isAudio(File file) {
        MimeTypeMap types = MimeTypeMap.getSingleton();
        return FileUtil.fileIsMimeType(file, "audio/*", types)
                || FileUtil.fileIsMimeType(file, "application/opus", types)
                || FileUtil.fileIsMimeType(file, "application/ogg", types);
    }
}
