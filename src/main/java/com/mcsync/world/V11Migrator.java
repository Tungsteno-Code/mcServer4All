package com.mcsync.world;

import com.mcsync.config.Config;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.model.ModsLock;
import com.mcsync.model.ModsManifest;
import com.mcsync.model.WorldProfile;
import com.mcsync.model.WorldRecipe;
import com.mcsync.setup.PrismInstances;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.storage.PrefixedStorage;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

/**
 * Automatic, reversible migration from v1.1 single-world layout to v2.0 multi-world layout (§12 of the plan).
 *
 * <ol>
 *     <li>Detects legacy keys at the root of the repository/bucket and/or single-world {@code config.json}.</li>
 *     <li>Creates a complete backup of existing keys under {@code migration_v1_backup/} (and {@code config.v1.1.backup.json}).</li>
 *     <li>Moves keys under {@code worlds/default/} and generates {@code world.json} + {@code mods.lock.json}
 *     from {@code mods_manifest.json} / {@code client_mods_manifest.json}.</li>
 *     <li>Migrates single-world fields in {@code config.json} into the {@code default} world entry.</li>
 *     <li>Remains reversible via {@link #rollback(Path, S3Storage)} as long as the backup is kept.</li>
 * </ol>
 */
public final class V11Migrator {

    private static final Logger log = LoggerFactory.getLogger(V11Migrator.class);

    public static final String DEFAULT_WORLD_ID = "default";
    public static final String BACKUP_PREFIX = "migration_v1_backup/";
    public static final String CONFIG_BACKUP_SUFFIX = ".v1.1.backup.json";

    private static final List<String> LEGACY_ROOT_KEYS = List.of(
            "manifest.json", "mods_manifest.json", "client_mods_manifest.json", "lock.json", "world_latest.tar.zst");
    private static final List<String> LEGACY_ROOT_PREFIXES = List.of(
            "world_blobs/", "modfiles/", "backups/", "conflicts/");

    public record MigrationResult(boolean configMigrated, boolean storageMigrated, Config updatedConfig, int movedKeys) {
    }

    private V11Migrator() {
    }

    /** True if {@code config} has legacy single-world settings and no {@code worlds[]} entries yet. */
    public static boolean needsConfigMigration(Config cfg) {
        return cfg != null && cfg.worlds().isEmpty() && cfg.serverDirectory() != null && !cfg.serverDirectory().isBlank();
    }

    /** True if {@code rootStorage} has v1.1 keys at its root and no {@code worlds/default/world.json}. */
    public static boolean needsStorageMigration(S3Storage rootStorage) {
        if (rootStorage.exists("worlds/" + DEFAULT_WORLD_ID + "/" + WorldStore.RECIPE_KEY)) {
            return false;
        }
        for (String key : LEGACY_ROOT_KEYS) {
            if (rootStorage.exists(key)) return true;
        }
        for (String prefix : LEGACY_ROOT_PREFIXES) {
            if (!rootStorage.list(prefix).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Migrates {@code config.json} in-place (saving a backup copy first) so single-world fields
     * become the {@code default} world in {@code worlds[]}.
     */
    public static Config migrateConfig(Path configFile, Config cfg) throws IOException {
        if (!needsConfigMigration(cfg)) return cfg;

        if (configFile != null && Files.isRegularFile(configFile)) {
            Path backup = configBackupPath(configFile);
            if (!Files.exists(backup)) {
                Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backup configurazione v1.1 salvato in {}", backup);
            }
        }

        String prismId = "";
        Path clientDir = cfg.clientInstanceDir();
        if (clientDir != null && clientDir.getParent() != null && clientDir.getParent().getFileName() != null) {
            prismId = clientDir.getParent().getFileName().toString();
        } else {
            String derived = PrismInstances.instanceName(clientDir);
            if (derived != null) prismId = derived;
        }

        WorldProfile def = new WorldProfile(
                DEFAULT_WORLD_ID,
                "Mondo Principale (default)",
                cfg.serverDirectory(),
                prismId,
                cfg.clientInstanceDirectory(),
                "CREATOR");

        Config next = cfg.withWorld(def).withActiveWorld(DEFAULT_WORLD_ID);
        if (configFile != null && Files.isRegularFile(configFile)) {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("worlds", next.worlds());
            updates.put("activeWorldId", DEFAULT_WORLD_ID);
            new ConfigFixer(configFile).set(updates);
        }
        log.info("Configurazione v1.1 migrata al mondo '{}'", DEFAULT_WORLD_ID);
        return next;
    }

    /**
     * Migrates legacy root keys in {@code rootStorage} into {@code worlds/default/}, keeping a full
     * backup under {@code migration_v1_backup/} and generating {@code world.json} + {@code mods.lock.json}.
     */
    public static int migrateStorage(Config cfg, S3Storage rootStorage) throws IOException {
        if (!needsStorageMigration(rootStorage)) return 0;

        List<S3Storage.Entry> all = rootStorage.list("");
        List<S3Storage.Entry> legacy = all.stream()
                .filter(e -> isLegacyRootKey(e.key()))
                .toList();
        if (legacy.isEmpty()) return 0;

        log.info("Rilevata struttura v1.1 nel repository ({} chiavi alla radice): avvio migrazione a worlds/{}/ ...",
                legacy.size(), DEFAULT_WORLD_ID);

        PrefixedStorage defaultWorldStorage = PrefixedStorage.forWorld(rootStorage, DEFAULT_WORLD_ID);

        // 1. Read legacy mod manifests before moving
        Optional<ModsManifest> serverMods = readManifest(rootStorage, "mods_manifest.json");
        Optional<ModsManifest> clientMods = readManifest(rootStorage, "client_mods_manifest.json");
        Map<String, Long> blobSizes = new HashMap<>();
        for (S3Storage.Entry e : legacy) {
            if (e.key().startsWith("modfiles/")) {
                blobSizes.put(e.key().substring("modfiles/".length()), e.size());
            }
        }

        // 2. Full backup under migration_v1_backup/ and copy under worlds/default/
        for (S3Storage.Entry e : legacy) {
            String key = e.key();
            rootStorage.copy(key, BACKUP_PREFIX + key);
            rootStorage.copy(key, defaultWorldStorage.qualify(key));
        }

        // 3. Generate world.json + mods.lock.json from the current modpack
        WorldRecipe recipe = ServerProfileDetector.initWorldRecipe(
                cfg.serverDir(), cfg.clientInstanceDir(), DEFAULT_WORLD_ID, "Mondo Principale (default)", cfg);
        ModsLock lock = buildLockFromLegacyManifests(recipe, serverMods.orElse(null), clientMods.orElse(null),
                blobSizes, cfg.playerId());

        defaultWorldStorage.put(WorldStore.RECIPE_KEY, Json.toBytes(recipe), "application/json");
        defaultWorldStorage.put(WorldStore.LOCK_KEY, Json.toBytes(lock), "application/json");

        WorldRegistry.register(rootStorage, new WorldRegistry.Entry(
                DEFAULT_WORLD_ID, recipe.displayName(), cfg.playerId(), Instant.now().getEpochSecond()));

        // 4. Delete the old root keys (backup in migration_v1_backup/ is preserved)
        rootStorage.deleteAll(legacy.stream().map(S3Storage.Entry::key).toList());

        log.info("Migrazione v1.1 -> v2.0 completata ({} chiavi spostate in worlds/{}/, backup in {}).",
                legacy.size(), DEFAULT_WORLD_ID, BACKUP_PREFIX);
        return legacy.size();
    }

    /** Runs both config and storage migration if needed. */
    public static MigrationResult migrateIfNeeded(Path configFile, Config cfg, S3Storage rootStorage) throws IOException {
        boolean cfgMigrated = false;
        Config updated = cfg;
        if (needsConfigMigration(updated)) {
            updated = migrateConfig(configFile, updated);
            cfgMigrated = true;
        }
        int moved = 0;
        if (rootStorage != null && needsStorageMigration(rootStorage)) {
            moved = migrateStorage(updated, rootStorage);
        }
        return new MigrationResult(cfgMigrated, moved > 0, updated, moved);
    }

    /**
     * Reverts the v1.1 -> v2.0 migration as long as {@code migration_v1_backup/} has not been deleted.
     */
    public static void rollback(Path configFile, S3Storage rootStorage) throws IOException {
        List<S3Storage.Entry> backups = rootStorage.list(BACKUP_PREFIX);
        if (backups.isEmpty()) {
            throw new IOException("Nessun backup di migrazione v1.1 trovato in " + BACKUP_PREFIX);
        }
        for (S3Storage.Entry e : backups) {
            String originalKey = e.key().substring(BACKUP_PREFIX.length());
            rootStorage.copy(e.key(), originalKey);
        }
        List<String> worldDefaultKeys = rootStorage.list("worlds/" + DEFAULT_WORLD_ID + "/").stream()
                .map(S3Storage.Entry::key).toList();
        if (!worldDefaultKeys.isEmpty()) {
            rootStorage.deleteAll(worldDefaultKeys);
        }
        if (rootStorage.exists(WorldRegistry.KEY)) {
            rootStorage.delete(WorldRegistry.KEY);
        }
        if (configFile != null) {
            Path cfgBackup = configBackupPath(configFile);
            if (Files.isRegularFile(cfgBackup)) {
                Files.copy(cfgBackup, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Rollback migrazione v1.1 completato ({} chiavi ripristinate alla radice).", backups.size());
    }

    public static ModsLock buildLockFromLegacyManifests(
            WorldRecipe recipe,
            ModsManifest serverManifest,
            ModsManifest clientManifest,
            Map<String, Long> blobSizes,
            String updatedBy) {
        Map<String, String> srv = serverManifest != null ? serverManifest.files() : Map.of();
        Map<String, String> cli = clientManifest != null ? clientManifest.files() : Map.of();

        Map<String, ModsLock.Entry> files = new TreeMap<>();
        Map<String, String> pinnedConfigs = new TreeMap<>();
        Map<String, String> serverFiles = new TreeMap<>();

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(srv.keySet());
        allPaths.addAll(cli.keySet());

        for (String rel : allPaths) {
            String sHash = srv.get(rel);
            String cHash = cli.get(rel);
            String hash = sHash != null ? sHash : cHash;
            long size = blobSizes != null ? blobSizes.getOrDefault(hash, -1L) : -1L;

            if (rel.startsWith("mods/") && rel.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                String side;
                if (clientManifest == null || (sHash != null && cHash != null)) {
                    side = ModsLock.SIDE_BOTH;
                } else if (sHash != null) {
                    side = ModsLock.SIDE_SERVER;
                } else {
                    side = ModsLock.SIDE_CLIENT;
                }
                files.put(rel, new ModsLock.Entry(hash, size, side, ModsLock.Source.blob()));
            } else {
                if (sHash != null) serverFiles.put(rel, sHash);
                if (cHash != null && (sHash == null || sHash.equalsIgnoreCase(cHash))) {
                    pinnedConfigs.put(rel, cHash);
                }
            }
        }

        return new ModsLock(
                ModsLock.SCHEMA_VERSION,
                1L,
                recipe.minecraft(),
                recipe.loader(),
                files,
                pinnedConfigs,
                serverFiles,
                updatedBy != null ? updatedBy : recipe.createdBy(),
                Instant.now().getEpochSecond());
    }

    private static Optional<ModsManifest> readManifest(S3Storage storage, String key) throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(key);
        if (f.isEmpty()) return Optional.empty();
        return Optional.of(Json.fromBytes(f.get().data(), ModsManifest.class));
    }

    private static boolean isLegacyRootKey(String key) {
        if (key.startsWith("worlds/") || key.startsWith(BACKUP_PREFIX) || key.equals(WorldRegistry.KEY)) {
            return false;
        }
        if (LEGACY_ROOT_KEYS.contains(key)) return true;
        for (String p : LEGACY_ROOT_PREFIXES) {
            if (key.startsWith(p)) return true;
        }
        return false;
    }

    private static Path configBackupPath(Path configFile) {
        return configFile.resolveSibling(configFile.getFileName().toString() + CONFIG_BACKUP_SUFFIX);
    }
}
