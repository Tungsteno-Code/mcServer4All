package com.mcsync.world;

import com.mcsync.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared content-addressed jar cache: {@code <cacheDir>/modfiles/<sha256>}, reused by every world. */
public final class ModCache {

    private final Path dir;

    public ModCache(Path cacheDir) {
        this.dir = cacheDir.resolve("modfiles");
    }

    public Path dir() {
        return dir;
    }

    public Path path(String sha256) {
        return dir.resolve(sha256.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean has(String sha256) {
        return Files.isRegularFile(path(sha256));
    }

    /** Stores a verified file in the cache (copy) and returns the cached path. */
    public Path put(String sha256, Path file) throws IOException {
        Files.createDirectories(dir);
        Path target = path(sha256);
        if (!Files.isRegularFile(target)) {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            FileUtils.moveWithRetry(tmp, target);
        }
        return target;
    }

    /** Cached file that is verified again if it was not hashed in this process. */
    public boolean verify(String sha256) {
        try {
            return has(sha256) && FileUtils.sha256(path(sha256)).equalsIgnoreCase(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    /** Hard link when possible (same filesystem), plain copy otherwise. */
    public static void link(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(target);
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
