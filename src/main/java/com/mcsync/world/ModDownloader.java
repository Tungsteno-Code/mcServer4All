package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.model.ModsLock;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Makes sure files of a lock are in the shared {@link ModCache}: parallel download from the public
 * CDN (Modrinth / CurseForge) or from the repo blob store, SHA-256 verified before use.
 */
public final class ModDownloader {

    private static final Logger log = LoggerFactory.getLogger(ModDownloader.class);

    /** A file to fetch; {@code size < 0} = unknown. */
    public record Wanted(String sha256, String name, long size, ModsLock.Source source) {
    }

    /** Callback for live download progress: {@code (doneCount, totalCount, fileName)}. */
    public interface ProgressListener {
        void onProgress(int done, int total, String currentFile);
    }

    private final ModCache cache;
    private final WorldStore store;
    private final String curseforgeApiKey;
    private final String curseforgeApiBase;
    private final int parallelism;
    private volatile ProgressListener progressListener;

    public ModDownloader(ModCache cache, WorldStore store, String curseforgeApiKey) {
        this(cache, store, curseforgeApiKey, "https://api.curseforge.com/v1", 8);
    }

    public ModDownloader(ModCache cache, WorldStore store, String curseforgeApiKey, String curseforgeApiBase,
                         int parallelism) {
        this.cache = cache;
        this.store = store;
        this.curseforgeApiKey = curseforgeApiKey == null ? "" : curseforgeApiKey;
        this.curseforgeApiBase = curseforgeApiBase;
        this.parallelism = Math.max(1, parallelism);
    }

    public ModDownloader withProgress(ProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    public ModCache cache() {
        return cache;
    }

    /** Fetches everything that is not cached yet. @return number of files downloaded */
    public int ensureAll(Collection<Wanted> wanted) throws IOException {
        Map<String, Wanted> missing = new LinkedHashMap<>();
        for (Wanted w : wanted) {
            if (!cached(w)) missing.putIfAbsent(w.sha256().toLowerCase(Locale.ROOT), w);
        }
        if (missing.isEmpty()) return 0;
        int total = missing.size();
        log.info("Download di {} file ({} thread) ...", total, Math.min(parallelism, total));
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, total), r -> {
            Thread t = new Thread(r, "mod-download");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.atomic.AtomicInteger doneCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Wanted w : missing.values()) {
                futures.add(pool.submit(() -> {
                    try {
                        fetch(w);
                        int cur = doneCounter.incrementAndGet();
                        int pct = (cur * 100) / total;
                        log.info("Download mod [{}/{}] - {}% ({})", cur, total, pct, w.name());
                        ProgressListener pl = progressListener;
                        if (pl != null) {
                            try {
                                pl.onProgress(cur, total, w.name());
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return null;
                    } catch (IOException e) {
                        return w.name() + ": " + e.getMessage();
                    }
                }));
            }
            List<String> errors = new ArrayList<>();
            for (Future<String> f : futures) {
                try {
                    String err = f.get();
                    if (err != null) errors.add(err);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrotto", e);
                } catch (ExecutionException e) {
                    errors.add(String.valueOf(e.getCause()));
                }
            }
            if (!errors.isEmpty()) {
                throw new IOException("Impossibile scaricare " + errors.size() + " file:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), errors));
            }
            return missing.size();
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean cached(Wanted w) {
        if (!cache.has(w.sha256())) return false;
        if (w.size() < 0) return true;
        try {
            return Files.size(cache.path(w.sha256())) == w.size();
        } catch (IOException e) {
            return false;
        }
    }

    private void fetch(Wanted w) throws IOException {
        Path tmp = cache.dir().resolve(w.sha256() + "." + Thread.currentThread().threadId() + ".dl");
        Files.createDirectories(cache.dir());
        List<String> failures = new ArrayList<>();
        try {
            ModsLock.Source src = w.source();
            if (src != null && !ModsLock.SRC_BLOB.equals(src.type())) {
                try {
                    String url = src.url();
                    if ((url == null || url.isBlank()) && ModsLock.SRC_CURSEFORGE.equals(src.type())) {
                        url = curseforgeUrl(src.id());
                        if (url == null || url.isBlank()) {
                            url = edgeFallbackFromIdAndName(src.id(), w.name());
                        }
                    }
                    if (url == null || url.isBlank()) throw new IOException("nessun URL di download");
                    log.debug("  download {}", w.name());
                    Http.download(url, Map.of(), tmp);
                    if (FileUtils.sha256(tmp).equalsIgnoreCase(w.sha256())) {
                        cache.put(w.sha256(), tmp);
                        return;
                    }
                    throw new IOException("hash SHA-256 diverso dall'atteso");
                } catch (IOException e) {
                    failures.add(src.type() + ": " + e.getMessage());
                    log.warn("Sorgente {} fallita per {} ({}): provo fallback CDN / store repo", src.type(), w.name(), e.getMessage());
                    if (ModsLock.SRC_CURSEFORGE.equals(src.type())) {
                        String edgeUrl = edgeFallbackFromIdAndName(src.id(), w.name());
                        if (edgeUrl != null) {
                            try {
                                Http.download(edgeUrl, Map.of(), tmp);
                                if (FileUtils.sha256(tmp).equalsIgnoreCase(w.sha256())) {
                                    cache.put(w.sha256(), tmp);
                                    return;
                                }
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
            }
            if (store != null && store.hasBlob(w.sha256())) {
                store.downloadBlob(w.sha256(), tmp);
                if (FileUtils.sha256(tmp).equalsIgnoreCase(w.sha256())) {
                    cache.put(w.sha256(), tmp);
                    return;
                }
                failures.add("blob: hash SHA-256 diverso dall'atteso");
            } else {
                failures.add("blob: non presente nel repo");
            }
            throw new IOException(String.join("; ", failures));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String curseforgeUrl(String id) throws IOException {
        if (curseforgeApiKey.isBlank() || id == null || !id.contains(":")) return null;
        String[] p = id.split(":");
        String body = Http.getString(curseforgeApiBase + "/mods/" + p[0] + "/files/" + p[1] + "/download-url",
                Map.of("x-api-key", curseforgeApiKey, "Accept", "application/json"));
        if (body == null) return null;
        JsonNode d = Json.mapper().readTree(body).path("data");
        return d.isTextual() ? d.asText() : null;
    }

    private static String edgeFallbackFromIdAndName(String id, String name) {
        if (id == null || !id.contains(":") || name == null) return null;
        try {
            long fid = Long.parseLong(id.split(":")[1]);
            String fileName = Path.of(name).getFileName().toString();
            return SourceResolver.CurseForgeFingerprint.edgeCdnFallbackUrl(fid, fileName);
        } catch (Exception e) {
            return null;
        }
    }
}
