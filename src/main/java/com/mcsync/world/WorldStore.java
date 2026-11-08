package com.mcsync.world;

import com.mcsync.model.ModsLock;
import com.mcsync.model.WorldRecipe;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything stored in the repo for ONE world, on top of the world-prefixed {@link S3Storage}
 * ({@code world.json}, {@code mods.lock.json}, {@code locks/v<N>.json}, {@code modfiles/<sha256>}).
 */
public final class WorldStore {

    private static final Logger log = LoggerFactory.getLogger(WorldStore.class);

    public static final String RECIPE_KEY = "world.json";
    public static final String LOCK_KEY = "mods.lock.json";
    public static final String LOCKS_PREFIX = "locks/";
    public static final String SERVERFILES_PREFIX = "serverfiles/";
    public static final String BLOB_PREFIX = "modfiles/";
    private static final String JSON = "application/json";
    private static final Pattern LOCK_VERSION = Pattern.compile("locks/v(\\d+)\\.json");

    /** Versions of {@code mods.lock.json} kept for rollback. */
    public static final int KEEP_LOCK_VERSIONS = 10;

    /** A lock as read from the repo, with what is needed for a conditional write. */
    public record LockRef(ModsLock lock, byte[] raw, String eTag, String sha256) {
    }

    /** The lock changed in the repo while we were preparing an update. */
    public static final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private final S3Storage storage;

    public WorldStore(S3Storage storage) {
        this.storage = storage;
    }

    public S3Storage storage() {
        return storage;
    }

    // ------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------

    public Optional<WorldRecipe> recipe() throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(RECIPE_KEY);
        return f.isEmpty() ? Optional.empty() : Optional.of(Json.fromBytes(f.get().data(), WorldRecipe.class));
    }

    public Optional<LockRef> lock() throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(LOCK_KEY);
        if (f.isEmpty()) return Optional.empty();
        return Optional.of(toRef(f.get().data(), f.get().eTag()));
    }

    static LockRef toRef(byte[] raw, String eTag) throws IOException {
        return new LockRef(Json.fromBytes(raw, ModsLock.class), raw, eTag, sha256(raw));
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(FileUtils.sha256().digest(data));
    }

    public List<Long> lockVersions() {
        List<Long> out = new ArrayList<>();
        for (S3Storage.Entry e : storage.list(LOCKS_PREFIX)) {
            Matcher m = LOCK_VERSION.matcher(e.key());
            if (m.matches()) out.add(Long.parseLong(m.group(1)));
        }
        Collections.sort(out);
        return out;
    }

    public Optional<ModsLock> lockVersion(long version) throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(LOCKS_PREFIX + "v" + version + ".json");
        return f.isEmpty() ? Optional.empty() : Optional.of(Json.fromBytes(f.get().data(), ModsLock.class));
    }

    // ------------------------------------------------------------------------------------------
    // Blobs
    // ------------------------------------------------------------------------------------------

    public boolean hasBlob(String sha256) {
        return storage.exists(BLOB_PREFIX + sha256);
    }

    public void downloadBlob(String sha256, Path target) throws IOException {
        storage.downloadFile(BLOB_PREFIX + sha256, target);
    }

    /**
     * Uploads the not-yet-present blobs (sha256 -&gt; file) together with {@code anchorKey} in one
     * commit (Git) or as individual puts (S3).
     */
    void stageBlobs(Map<String, Path> blobs, String anchorKey, byte[] anchorBytes, String message) throws IOException {
        Set<String> existing = new HashSet<>();
        storage.list(BLOB_PREFIX).forEach(e -> existing.add(e.key()));
        Map<String, Path> toStage = new LinkedHashMap<>();
        blobs.forEach((sha, file) -> {
            if (!existing.contains(BLOB_PREFIX + sha)) toStage.put(BLOB_PREFIX + sha, file);
        });
        if (storage.isGitBackend()) {
            storage.git().commitSessionDeltaBatch(toStage, List.of(), anchorKey, anchorBytes, message);
        } else {
            for (Map.Entry<String, Path> e : toStage.entrySet()) {
                storage.uploadFile(e.getKey(), e.getValue(), "application/octet-stream");
            }
            storage.put(anchorKey, anchorBytes, JSON);
        }
        log.info("{}: {} blob caricati ({} già presenti)", message, toStage.size(), blobs.size() - toStage.size());
    }

    // ------------------------------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------------------------------

    /** First publication of a world: recipe + blobs, then the lock created atomically. */
    public LockRef publishNew(WorldRecipe recipe, ModsLock lock, Map<String, Path> blobs) throws IOException {
        stageBlobs(blobs, RECIPE_KEY, Json.toBytes(recipe), "mcsync: create world " + recipe.worldId());
        byte[] raw = Json.toBytes(lock);
        Optional<String> tag = storage.putIfAbsent(LOCK_KEY, raw, JSON);
        if (tag.isEmpty()) throw new ConflictException("Il mondo '" + recipe.worldId() + "' esiste già nel repo");
        return new LockRef(lock, raw, tag.get(), sha256(raw));
    }

    /**
     * Publishes {@code next} on top of {@code base}: stages any new content-addressed blobs, updates
     * {@code mods.lock.json} with a <b>conditional</b> write ({@code putIfMatch}) so two simultaneous
     * updates can never silently overwrite each other, and archives the superseded lock in {@code locks/}.
     */
    public LockRef update(ModsLock next, Map<String, Path> blobs, LockRef base, WorldRecipe recipe) throws IOException {
        if (next.modpackVersion() <= base.lock().modpackVersion()) {
            throw new IOException("La nuova versione del modpack deve essere maggiore di v" + base.lock().modpackVersion());
        }
        // Check/upload only missing content-addressed blobs first (using batch commit on Git!)
        Set<String> existing = new HashSet<>();
        storage.list(BLOB_PREFIX).forEach(e -> existing.add(e.key()));
        Map<String, Path> missingBlobs = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : blobs.entrySet()) {
            String key = BLOB_PREFIX + e.getKey();
            if (!existing.contains(key)) {
                missingBlobs.put(key, e.getValue());
            }
        }
        String archiveKey = LOCKS_PREFIX + "v" + base.lock().modpackVersion() + ".json";
        if (!missingBlobs.isEmpty()) {
            if (storage.isGitBackend()) {
                storage.git().commitSessionDeltaBatch(missingBlobs, List.of(), archiveKey, base.raw(),
                        "mcsync: stage " + missingBlobs.size() + " blobs for v" + next.modpackVersion());
            } else {
                for (Map.Entry<String, Path> e : missingBlobs.entrySet()) {
                    storage.uploadFile(e.getKey(), e.getValue(), "application/octet-stream");
                }
            }
        }
        // Conditional write on mods.lock.json
        byte[] raw = Json.toBytes(next);
        Optional<String> tag = storage.putIfMatch(LOCK_KEY, raw, JSON, base.eTag());
        if (tag.isEmpty()) {
            throw new ConflictException("Il modpack è stato modificato da qualcun altro nel frattempo: "
                    + "riparti dalla versione aggiornata");
        }
        if (!storage.isGitBackend() || missingBlobs.isEmpty()) {
            storage.put(archiveKey, base.raw(), JSON);
        }
        if (recipe != null) storage.put(RECIPE_KEY, Json.toBytes(recipe), JSON);
        pruneHistory();
        gcBlobs(next);
        return new LockRef(next, raw, tag.get(), sha256(raw));
    }

    /** Keeps only the newest {@link #KEEP_LOCK_VERSIONS} archived locks. */
    void pruneHistory() {
        List<Long> versions = lockVersions();
        int excess = versions.size() - KEEP_LOCK_VERSIONS;
        if (excess <= 0) return;
        List<String> victims = versions.subList(0, excess).stream().map(v -> LOCKS_PREFIX + "v" + v + ".json").toList();
        storage.deleteAll(victims);
    }

    /** Removes blobs not referenced by the current lock nor by the archived ones (rollback needs them). */
    public void gcBlobs(ModsLock current) {
        try {
            Set<String> referenced = new HashSet<>(blobShas(current));
            for (long v : lockVersions()) lockVersion(v).ifPresent(l -> referenced.addAll(blobShas(l)));
            List<String> orphans = storage.list(BLOB_PREFIX).stream().map(S3Storage.Entry::key)
                    .filter(k -> !referenced.contains(k.substring(BLOB_PREFIX.length()))).toList();
            if (!orphans.isEmpty()) {
                log.info("Rimozione di {} blob non più referenziati", orphans.size());
                storage.deleteAll(orphans);
            }
        } catch (Exception e) {
            log.warn("Pulizia blob fallita: {}", e.getMessage());
        }
    }

    /** SHA-256 of every file of the lock that lives in the blob store. */
    public static Set<String> blobShas(ModsLock lock) {
        Set<String> s = new HashSet<>();
        lock.files().values().stream().filter(e -> ModsLock.SRC_BLOB.equals(e.source().type()))
                .forEach(e -> s.add(e.sha256()));
        s.addAll(lock.pinnedConfigs().values());
        s.addAll(lock.serverFiles().values());
        s.addAll(lock.overridesBundles().values());
        return s;
    }

    public void putRecipe(WorldRecipe recipe) {
        storage.put(RECIPE_KEY, Json.toBytes(recipe), JSON);
    }

    /** Copies an old archived lock back as the current one (new, higher version number). */
    public LockRef rollback(long targetVersion, LockRef base, String by) throws IOException {
        ModsLock old = lockVersion(targetVersion)
                .orElseThrow(() -> new IOException("Versione v" + targetVersion + " non disponibile per il rollback"));
        long next = base.lock().modpackVersion() + 1;
        ModsLock restored = new ModsLock(old.schemaVersion(), next, old.minecraft(), old.loader(), old.files(),
                old.pinnedConfigs(), old.serverFiles(), old.overridesBundles(), by, java.time.Instant.now().getEpochSecond());
        return update(restored, Map.of(), base, null);
    }
}
