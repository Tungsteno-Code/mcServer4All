package com.mcsync.core;

import com.mcsync.config.Config;
import com.mcsync.model.ModsManifest;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Glob;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import com.mcsync.world.LockedModsSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Mod &amp; configuration synchronisation.
 *
 * <p>Two independent scopes are handled, each with its own manifest:</p>
 * <ul>
 *     <li>{@code server}: {@code mods/} and {@code config/} inside {@code serverDirectory}
 *     ({@code mods_manifest.json}),</li>
 *     <li>{@code client}: {@code mods/} and {@code config/} inside the Prism instance
 *     ({@code client_mods_manifest.json}), only if {@code clientInstanceDirectory} is set.</li>
 * </ul>
 * File contents are stored content-addressed under {@code modfiles/<sha256>} so identical files
 * are uploaded once and renames cost nothing.
 *
 * <p>Modes:</p>
 * <ul>
 *     <li><b>DIFFERENTIAL</b>: the host publishes its files (three-way check against the last
 *     synced fingerprint prevents an outdated host from reverting a newer remote modpack);
 *     joining clients download changed files and delete obsolete {@code .jar}s.</li>
 *     <li><b>STRICT</b>: any mismatch aborts the launch with a detailed diff.</li>
 *     <li><b>DISABLED</b>: no checks.</li>
 *     <li><b>LOCKED</b> (v2.0, multi-world): the world's {@code mods.lock.json} is the only source of
 *     truth; nothing is scanned or merged, see {@link LockedModsSync}.</li>
 * </ul>
 */
public final class ModsSyncManager {

    private static final Logger log = LoggerFactory.getLogger(ModsSyncManager.class);

    private static final String BLOB_PREFIX = "modfiles/";
    private static final List<String> ROOTS = List.of("mods", "config");

    private final Config cfg;
    private final S3Storage storage;
    private final LocalStateStore state;
    private final Glob excludes;
    private LockedModsSync lockedSync;
    private volatile List<String> lockedStartCommand;

    /** One synchronised scope. */
    public record Scope(String name, Path root, String manifestKey) {
    }

    /** Difference between local and remote manifests. */
    public record Diff(Map<String, String> missingLocally, Map<String, String[]> changed, Map<String, String> extraLocally) {
        public boolean isEmpty() {
            return missingLocally.isEmpty() && changed.isEmpty() && extraLocally.isEmpty();
        }
    }

    /** Thrown in STRICT mode when local and remote files differ. */
    public static final class ModMismatchException extends Exception {
        public ModMismatchException(String message) {
            super(message);
        }
    }

    public ModsSyncManager(Config cfg, S3Storage storage, LocalStateStore state) {
        this.cfg = cfg;
        this.storage = storage;
        this.state = state;
        this.excludes = new Glob(cfg.modSyncExcludes());
    }

    public boolean isLocked() {
        return mode().equals("LOCKED");
    }

    private synchronized LockedModsSync locked() {
        if (lockedSync == null) lockedSync = new LockedModsSync(cfg, storage, state);
        return lockedSync;
    }

    /** In LOCKED mode: the server start command produced by the last {@link #syncAsHost()}. */
    public Optional<List<String>> lockedStartCommand() {
        return Optional.ofNullable(lockedStartCommand);
    }

    public List<Scope> scopes() {
        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope("server", cfg.serverDir(), "mods_manifest.json"));
        Path client = cfg.clientInstanceDir();
        if (client != null) {
            if (Files.isDirectory(client)) scopes.add(new Scope("client", client, "client_mods_manifest.json"));
            else log.warn("clientInstanceDirectory does not exist: {} (client mods not synced)", client);
        }
        return scopes;
    }

    // ------------------------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------------------------

    /** Called by the host after acquiring the lock, before the server boots. */
    public void syncAsHost() throws IOException, ModMismatchException {
        if (mode().equals("DISABLED")) return;
        if (isLocked()) {
            lockedStartCommand = locked().syncAsHost();
            return;
        }
        for (Scope scope : scopes()) {
            ModsManifest local = scan(scope.root());
            Optional<ModsManifest> remote = fetch(scope);
            if (mode().equals("STRICT")) {
                strictCheck(scope, local, remote);
                continue;
            }
            if (remote.isEmpty()) {
                log.info("[{}] No remote mod manifest yet: publishing {} files", scope.name(), local.files().size());
                publish(scope, local);
                continue;
            }
            Diff diff = diff(local, remote.get());
            if (diff.isEmpty()) {
                log.info("[{}] Mods & configs in sync ({} files)", scope.name(), local.files().size());
                rememberFingerprint(scope, local.fingerprint());
                continue;
            }
            String lastSynced = "client".equals(scope.name())
                    ? state.get().clientModsFingerprint() : state.get().serverModsFingerprint();
            boolean remoteChanged = lastSynced == null || !lastSynced.equals(remote.get().fingerprint());
            boolean localChanged = lastSynced == null || !lastSynced.equals(local.fingerprint());

            if (remoteChanged && !localChanged) {
                log.info("[{}] Remote modpack was updated by {}: downloading changes", scope.name(),
                        remote.get().publishedBy());
                apply(scope, local, remote.get());
            } else {
                if (remoteChanged) {
                    log.warn("[{}] Both local and remote mods changed since the last sync: the host's files win",
                            scope.name());
                    printDiff(scope, diff);
                }
                log.info("[{}] Publishing local modpack changes ({} differences)", scope.name(),
                        diff.missingLocally().size() + diff.changed().size() + diff.extraLocally().size());
                publish(scope, local);
            }
        }
    }

    /**
     * Called before joining someone else's server.
     *
     * <p>With {@code clientSyncScope = MODS_ONLY} (default) only the {@code .jar} files in
     * {@code mods/} are verified: configs are left untouched so the player's keybinds, shaders and
     * audio settings survive, and Forge/Fabric sync the critical server configs over the network
     * anyway. If the remote manifest did not change since the last successful sync on this PC, the
     * per-file hashing is skipped completely (quick-hash cache in {@code .mcsync/state.json}).</p>
     */
    public void syncAsClient() throws IOException, ModMismatchException {
        if (mode().equals("DISABLED")) return;
        if (isLocked()) {
            locked().syncAsClient();
            return;
        }
        boolean modsOnly = cfg.clientModsOnly();
        for (Scope scope : scopes()) {
            Optional<ModsManifest> remoteFull = fetch(scope);
            if (remoteFull.isEmpty()) {
                log.info("[{}] No remote mod manifest: nothing to verify", scope.name());
                continue;
            }
            ModsManifest remote = modsOnly ? modsOnlyView(remoteFull.get()) : remoteFull.get();
            String cacheKey = scope.name() + (modsOnly ? ":mods" : ":all");
            String remoteFp = remote.fingerprint();
            if (remoteFp.equals(state.get().quickSyncCache().get(cacheKey)) && allPresent(scope, remote)) {
                log.info("[{}] Modpack unchanged since the last sync ({} files): quick start, per-file check skipped",
                        scope.name(), remote.files().size());
                continue;
            }
            ModsManifest localFull = scan(scope.root(), modsOnly);
            if (mode().equals("STRICT")) {
                strictCheck(scope, localFull, Optional.of(remote));
                rememberQuick(cacheKey, remoteFp);
                continue;
            }
            Diff d = diff(localFull, remote);
            if (d.isEmpty()) {
                log.info("[{}] {} in sync ({} files)", scope.name(), modsOnly ? "Mods" : "Mods & configs",
                        localFull.files().size());
            } else {
                apply(scope, localFull, remote);
            }
            if (!modsOnly) rememberFingerprint(scope, remote.fingerprint());
            rememberQuick(cacheKey, remoteFp);
        }
    }

    /**
     * Cheap check (no hashing) used by the GUI: true if the remote modpack differs from what this PC
     * synchronised last time.
     */
    public boolean hasPendingUpdates() {
        if (mode().equals("DISABLED")) return false;
        if (isLocked()) return locked().hasPendingUpdates();
        try {
            boolean modsOnly = cfg.clientModsOnly();
            for (Scope scope : scopes()) {
                Optional<ModsManifest> remoteFull = fetch(scope);
                if (remoteFull.isEmpty()) continue;
                ModsManifest remote = modsOnly ? modsOnlyView(remoteFull.get()) : remoteFull.get();
                String cacheKey = scope.name() + (modsOnly ? ":mods" : ":all");
                boolean knownAsClient = remote.fingerprint().equals(state.get().quickSyncCache().get(cacheKey));
                String hostFp = "client".equals(scope.name())
                        ? state.get().clientModsFingerprint() : state.get().serverModsFingerprint();
                boolean knownAsHost = remoteFull.get().fingerprint().equals(hostFp);
                if (!knownAsClient && !knownAsHost) return true;
            }
            return false;
        } catch (IOException e) {
            log.debug("Mod status check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Only {@code mods/**.jar} entries of a manifest. */
    public static ModsManifest modsOnlyView(ModsManifest m) {
        Map<String, String> files = new TreeMap<>();
        m.files().forEach((k, v) -> {
            if (isModJar(k)) files.put(k, v);
        });
        return new ModsManifest(m.schemaVersion(), m.lastUpdated(), m.publishedBy(), files);
    }

    static boolean isModJar(String rel) {
        return rel.startsWith("mods/") && rel.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private boolean allPresent(Scope scope, ModsManifest m) {
        for (String rel : m.files().keySet()) {
            if (!Files.isRegularFile(scope.root().resolve(rel))) return false;
        }
        if (cfg.clientModsOnly()) {
            // An extra jar (e.g. a mod added by hand) would cause a registry mismatch: rescan if the count differs.
            try (Stream<Path> s = Files.list(scope.root().resolve("mods"))) {
                long jars = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .filter(p -> !excludes.matches("mods/" + p.getFileName())).count();
                long expected = m.files().keySet().stream().filter(k -> k.indexOf('/', 5) < 0).count();
                return jars == expected;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private void rememberQuick(String key, String fp) {
        state.update(s -> s.withQuickSync(key, fp));
    }

    /** Explicitly publishes the local files of every scope (CLI {@code push-mods}). */
    public void forcePublish() throws IOException {
        if (isLocked()) {
            throw new IOException("Mondo con modpack bloccato: usa 'modpack update' per pubblicare un nuovo modpack");
        }
        for (Scope scope : scopes()) publish(scope, scan(scope.root()));
    }

    /** Explicitly downloads the remote files of every scope (CLI {@code pull-mods}). */
    public void forcePull() throws IOException {
        if (isLocked()) {
            lockedStartCommand = locked().syncAsHost();
            locked().syncAsClient();
            return;
        }
        for (Scope scope : scopes()) {
            Optional<ModsManifest> remote = fetch(scope);
            if (remote.isPresent()) apply(scope, scan(scope.root()), remote.get());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Manifest generation & comparison
    // ------------------------------------------------------------------------------------------

    /** Recursively scans mods/ and config/ under {@code root}, hashing the tracked extensions. */
    public ModsManifest scan(Path root) throws IOException {
        return scan(root, false);
    }

    /** @param modsOnly only hash {@code mods/**.jar} (client MODS_ONLY scope) */
    public ModsManifest scan(Path root, boolean modsOnly) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String sub : modsOnly ? List.of("mods") : ROOTS) {
            Path dir = root.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            List<Path> paths;
            try (Stream<Path> s = Files.walk(dir)) {
                paths = s.filter(Files::isRegularFile).toList();
            }
            for (Path p : paths) {
                String rel = FileUtils.toPortable(root.relativize(p));
                if (!isTracked(rel) || modsOnly && !isModJar(rel)) continue;
                files.put(rel, FileUtils.sha256(p));
            }
        }
        return new ModsManifest(ModsManifest.SCHEMA_VERSION, Instant.now().getEpochSecond(), cfg.playerId(), files);
    }

    private boolean isTracked(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        boolean ext = cfg.modSyncExtensions().stream().anyMatch(lower::endsWith);
        return ext && !excludes.matches(rel);
    }

    public static Diff diff(ModsManifest local, ModsManifest remote) {
        Map<String, String> missing = new TreeMap<>();
        Map<String, String[]> changed = new TreeMap<>();
        Map<String, String> extra = new TreeMap<>();
        remote.files().forEach((path, hash) -> {
            String mine = local.files().get(path);
            if (mine == null) missing.put(path, hash);
            else if (!mine.equalsIgnoreCase(hash)) changed.put(path, new String[]{mine, hash});
        });
        local.files().forEach((path, hash) -> {
            if (!remote.files().containsKey(path)) extra.put(path, hash);
        });
        return new Diff(missing, changed, extra);
    }

    // ------------------------------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------------------------------

    private Optional<ModsManifest> fetch(Scope scope) throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(scope.manifestKey());
        if (f.isEmpty()) return Optional.empty();
        return Optional.of(Json.fromBytes(f.get().data(), ModsManifest.class));
    }

    private void publish(Scope scope, ModsManifest local) throws IOException {
        Set<String> uploaded = new HashSet<>();
        Set<String> existing = new HashSet<>();
        storage.list(BLOB_PREFIX).forEach(entry -> existing.add(entry.key()));

        if (storage.isGitBackend()) {
            Map<String, Path> toStage = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : local.files().entrySet()) {
                String hash = e.getValue();
                if (!uploaded.add(hash)) continue;
                String key = BLOB_PREFIX + hash;
                if (existing.contains(key)) continue;
                log.info("[{}]  stage {}", scope.name(), e.getKey());
                toStage.put(key, scope.root().resolve(e.getKey()));
            }
            storage.git().commitSessionDeltaBatch(
                    toStage,
                    List.of(),
                    scope.manifestKey(),
                    Json.toBytes(local),
                    "mcsync: publish " + scope.name() + " mods (" + toStage.size() + " new files)"
            );
            rememberFingerprint(scope, local.fingerprint());
            log.info("[{}] Published manifest ({} files, {} new uploads)", scope.name(), local.files().size(), toStage.size());
            garbageCollectBlobs();
            return;
        }

        int count = 0;
        for (Map.Entry<String, String> e : local.files().entrySet()) {
            String hash = e.getValue();
            if (!uploaded.add(hash)) continue;
            String key = BLOB_PREFIX + hash;
            if (existing.contains(key)) continue;
            log.info("[{}]  upload {}", scope.name(), e.getKey());
            storage.uploadFile(key, scope.root().resolve(e.getKey()), "application/octet-stream");
            count++;
        }
        storage.put(scope.manifestKey(), Json.toBytes(local), "application/json");
        rememberFingerprint(scope, local.fingerprint());
        log.info("[{}] Published manifest ({} files, {} new uploads)", scope.name(), local.files().size(), count);
        garbageCollectBlobs();
    }

    /** Makes the local folder match the remote manifest. Obsolete .jar files in mods/ are deleted. */
    private void apply(Scope scope, ModsManifest local, ModsManifest remote) throws IOException {
        Diff diff = diff(local, remote);
        printDiff(scope, diff);
        Path tmpDir = cfg.stateDir().resolve("mods_tmp");
        Files.createDirectories(tmpDir);

        Map<String, String> toDownload = new TreeMap<>(diff.missingLocally());
        diff.changed().forEach((p, h) -> toDownload.put(p, h[1]));
        for (Map.Entry<String, String> e : toDownload.entrySet()) {
            Path target = scope.root().resolve(e.getKey()).normalize();
            if (!target.startsWith(scope.root())) throw new IOException("Unsafe path in manifest: " + e.getKey());
            Path tmp = tmpDir.resolve(e.getValue());
            log.info("[{}]  download {}", scope.name(), e.getKey());
            storage.downloadFile(BLOB_PREFIX + e.getValue(), tmp);
            String actual = FileUtils.sha256(tmp);
            if (!actual.equalsIgnoreCase(e.getValue())) {
                Files.deleteIfExists(tmp);
                throw new IOException("Checksum mismatch for " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            FileUtils.moveWithRetry(tmp, target);
        }
        for (String extra : diff.extraLocally().keySet()) {
            if (extra.startsWith("mods/") && extra.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                log.info("[{}]  delete obsolete {}", scope.name(), extra);
                FileUtils.deleteWithRetry(scope.root().resolve(extra));
            }
        }
        FileUtils.deleteRecursively(tmpDir);
        rememberFingerprint(scope, remote.fingerprint());
        log.info("[{}] Mods synchronised with remote manifest by {}", scope.name(), remote.publishedBy());
    }

    private void strictCheck(Scope scope, ModsManifest local, Optional<ModsManifest> remote) throws IOException, ModMismatchException {
        if (remote.isEmpty()) {
            log.info("[{}] STRICT mode, no remote manifest yet: publishing reference manifest", scope.name());
            publish(scope, local);
            return;
        }
        Diff diff = diff(local, remote.get());
        if (diff.isEmpty()) {
            log.info("[{}] STRICT check passed ({} files)", scope.name(), local.files().size());
            return;
        }
        throw new ModMismatchException("Mod/config mismatch in scope '" + scope.name() + "':"
                + System.lineSeparator() + renderDiff(diff)
                + "Fix the files manually or set \"modSyncMode\": \"DIFFERENTIAL\".");
    }

    /** Removes content blobs not referenced by any manifest. */
    private void garbageCollectBlobs() {
        try {
            Set<String> referenced = new HashSet<>();
            for (String key : List.of("mods_manifest.json", "client_mods_manifest.json")) {
                Optional<S3Storage.Fetched> f = storage.get(key);
                if (f.isPresent()) referenced.addAll(Json.fromBytes(f.get().data(), ModsManifest.class).files().values());
            }
            List<String> orphans = storage.list(BLOB_PREFIX).stream().map(S3Storage.Entry::key)
                    .filter(k -> !referenced.contains(k.substring(BLOB_PREFIX.length()))).toList();
            if (!orphans.isEmpty()) {
                log.info("Removing {} unreferenced mod blob(s)", orphans.size());
                storage.deleteAll(orphans);
            }
        } catch (Exception e) {
            log.warn("Mod blob cleanup failed: {}", e.getMessage());
        }
    }

    private void rememberFingerprint(Scope scope, String fp) {
        state.update(s -> s.withModsFingerprint(scope.name(), fp));
    }

    private void printDiff(Scope scope, Diff diff) {
        if (diff.isEmpty()) return;
        log.info("[{}] Differences against remote:{}{}", scope.name(), System.lineSeparator(), renderDiff(diff));
    }

    public static String renderDiff(Diff diff) {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        diff.missingLocally().forEach((p, h) -> sb.append("  [MISSING]  ").append(p).append("  remote=").append(shortHash(h)).append(nl));
        diff.changed().forEach((p, h) -> sb.append("  [CHANGED]  ").append(p).append("  local=").append(shortHash(h[0]))
                .append(" remote=").append(shortHash(h[1])).append(nl));
        diff.extraLocally().forEach((p, h) -> sb.append("  [EXTRA]    ").append(p).append("  local=").append(shortHash(h)).append(nl));
        return sb.toString();
    }

    private static String shortHash(String h) {
        return h == null ? "-" : h.substring(0, Math.min(12, h.length()));
    }

    private String mode() {
        return cfg.modSyncMode();
    }
}
