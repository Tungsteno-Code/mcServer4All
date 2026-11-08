package com.mcsync.world;

import com.mcsync.model.ModsLock;
import com.mcsync.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Builds {@code mods/} (and pinned configs / server files) from a {@link ModsLock}: no scanning,
 * no merging. The lock is the only source of truth; jars not in the lock are removed.
 */
public final class LockMaterializer {

    private static final Logger log = LoggerFactory.getLogger(LockMaterializer.class);

    private final ModDownloader downloader;

    public LockMaterializer(ModDownloader downloader) {
        this.downloader = downloader;
    }

    /** Result counters for logging. */
    public record Result(int downloaded, int linked, int removed, int configs) {
    }

    /** Server: jars with side server/both, extra jars removed, then (optionally) the server files / overrides bundle. */
    public Result materializeServer(ModsLock lock, Path serverDir, boolean applyServerFiles) throws IOException {
        Map<String, ModsLock.Entry> mine = new TreeMap<>();
        lock.files().forEach((k, v) -> {
            if (v.onServer()) mine.put(k, v);
        });
        List<ModDownloader.Wanted> wanted = wantedJars(mine);
        Map<String, String> files = new TreeMap<>();
        if (applyServerFiles) {
            lock.overridesBundles().forEach((partName, sha) ->
                    wanted.add(new ModDownloader.Wanted(sha, partName, -1, ModsLock.Source.blob())));
            lock.serverFiles().forEach((k, sha) -> {
                files.put(k, sha);
                wanted.add(new ModDownloader.Wanted(sha, k, -1, ModsLock.Source.blob()));
            });
            lock.pinnedConfigs().forEach((k, sha) -> {
                files.putIfAbsent(k, sha);
                wanted.add(new ModDownloader.Wanted(sha, k, -1, ModsLock.Source.blob()));
            });
        }
        int downloaded = downloader.ensureAll(wanted);
        int[] counts = linkJars(mine, serverDir);
        int configs = 0;
        if (applyServerFiles) {
            for (String sha : lock.overridesBundles().values()) {
                configs += OverridesBundler.extract(downloader.cache().path(sha), serverDir, false);
            }
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (placeFile(serverDir, e.getKey(), e.getValue())) configs++;
            }
        }
        return new Result(downloaded, counts[0], counts[1], configs);
    }

    /** Client (Prism game dir): jars with side client/both, extra jars removed, overrides bundle + pinned configs enforced. */
    public Result materializeClient(ModsLock lock, Path gameDir, boolean verifyPinned) throws IOException {
        Map<String, ModsLock.Entry> mine = new TreeMap<>();
        lock.files().forEach((k, v) -> {
            if (v.onClient()) mine.put(k, v);
        });
        List<ModDownloader.Wanted> wanted = wantedJars(mine);
        if (verifyPinned) {
            lock.overridesBundles().forEach((partName, sha) ->
                    wanted.add(new ModDownloader.Wanted(sha, partName, -1, ModsLock.Source.blob())));
            lock.pinnedConfigs().forEach((k, sha) -> wanted.add(new ModDownloader.Wanted(sha, k, -1, ModsLock.Source.blob())));
        }
        int downloaded = downloader.ensureAll(wanted);
        int[] counts = linkJars(mine, gameDir);
        int configs = 0;
        if (verifyPinned) {
            for (String sha : lock.overridesBundles().values()) {
                configs += OverridesBundler.extract(downloader.cache().path(sha), gameDir, true);
            }
            for (Map.Entry<String, String> e : lock.pinnedConfigs().entrySet()) {
                if (placeFile(gameDir, e.getKey(), e.getValue())) configs++;
            }
        }
        return new Result(downloaded, counts[0], counts[1], configs);
    }

    private static List<ModDownloader.Wanted> wantedJars(Map<String, ModsLock.Entry> entries) {
        List<ModDownloader.Wanted> w = new ArrayList<>();
        entries.forEach((rel, e) -> w.add(new ModDownloader.Wanted(e.sha256(), rel, e.size(), e.source())));
        return w;
    }

    /** @return {linked, removed} */
    private int[] linkJars(Map<String, ModsLock.Entry> entries, Path root) throws IOException {
        ModCache cache = downloader.cache();
        int linked = 0, removed = 0;
        Path rootAbs = root.toAbsolutePath().normalize();
        for (Map.Entry<String, ModsLock.Entry> e : entries.entrySet()) {
            Path target = safe(rootAbs, e.getKey());
            if (Files.isRegularFile(target) && Files.size(target) == e.getValue().size()
                    && !needsRelink(target, cache.path(e.getValue().sha256()))) {
                continue;
            }
            ModCache.link(cache.path(e.getValue().sha256()), target);
            linked++;
        }
        Path mods = rootAbs.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (Stream<Path> s = Files.walk(mods)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".jar") || name.endsWith(".jar.disabled"))) continue;
                    String rel = FileUtils.toPortable(rootAbs.relativize(p));
                    if (!entries.containsKey(rel)) {
                        log.info("  rimuovo {} (non è nel modpack bloccato)", rel);
                        FileUtils.deleteWithRetry(p);
                        removed++;
                    }
                }
            }
        }
        return new int[]{linked, removed};
    }

    /** Size matches; a hard link to the cache is trusted, an independent copy is re-hashed once. */
    private static boolean needsRelink(Path target, Path cached) {
        try {
            if (Files.isSameFile(target, cached)) return false;
            return !FileUtils.sha256(target).equalsIgnoreCase(FileUtils.sha256(cached));
        } catch (IOException e) {
            return true;
        }
    }

    /** Copies the blob to {@code root/rel} unless it is already identical. @return true if written */
    private boolean placeFile(Path root, String rel, String sha256) throws IOException {
        Path rootAbs = root.toAbsolutePath().normalize();
        Path target = safe(rootAbs, rel);
        Path cached = downloader.cache().path(sha256);
        if (Files.isRegularFile(target)
                && Files.size(target) == Files.size(cached)
                && FileUtils.sha256(target).equalsIgnoreCase(sha256)) {
            return false;
        }
        Files.createDirectories(target.getParent());
        Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static Path safe(Path root, String rel) throws IOException {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || rel.startsWith("/") || rel.contains("..")) {
            throw new IOException("Percorso non sicuro nel lock: " + rel);
        }
        return p;
    }
}
