package com.mcsync;

import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.core.ModsSyncManager;
import com.mcsync.core.StorageSyncManager;
import com.mcsync.diagnostics.LogAnalyzer;
import com.mcsync.model.*;
import com.mcsync.notify.DiscordNotifier;
import com.mcsync.setup.PrismInstances;
import com.mcsync.storage.PrefixedStorage;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import com.mcsync.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end JUnit 5 verification of the v2.0 multi-world &amp; locked-modpack plan
 * (Phases 1–6 of {@code PIANIFICAZIONE-DEFINITIVA.md}).
 */
class MultiWorldV2Test {

    @TempDir
    Path tempDir;

    private Path initBareGitRemote() throws Exception {
        Path bare = tempDir.resolve("remote-repo.git");
        Files.createDirectories(bare);
        Process p = new ProcessBuilder("git", "init", "--bare", bare.toString())
                .redirectErrorStream(true)
                .start();
        assertEquals(0, p.waitFor(), "git init --bare failed");
        return bare;
    }

    private Config baseGitConfig(Path pcDir, String playerId, Path bareRepo) throws IOException {
        Path serverDir = pcDir.resolve("default-server");
        Files.createDirectories(serverDir.resolve("world"));
        return new Config(
                playerId,
                "100.64.0.10",
                "GIT",
                bareRepo.toUri().toString(),
                "",
                "mcsync-state",
                "",
                "auto",
                "",
                "",
                "",
                serverDir.toString(),
                List.of("java", "-jar", "server.jar", "nogui"),
                "world",
                List.of(),
                "",
                10,
                60,
                3,
                25565,
                true,
                "",
                List.of("player_alpha", "player_beta"),
                true,
                60,
                15,
                900,
                900,
                60,
                900,
                "auto",
                "DIFFERENTIAL",
                "",
                null,
                List.of("mods/oculus*", "mods/sodium*"),
                false,
                "MODS_ONLY",
                false,
                "",
                List.of(),
                "",
                pcDir.resolve("servers").toString(),
                "",
                pcDir.resolve("cache").toString()
        );
    }

    private static Path createPrismInstanceFixture(Path dir, String name, String mc, String loaderType, String loaderVer)
            throws IOException {
        Path inst = dir.resolve(name);
        Path game = inst.resolve("minecraft");
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("config"));
        Files.writeString(inst.resolve("instance.cfg"), "[General]\nname=" + name + "\n");
        String uid = switch (loaderType) {
            case "forge" -> "net.minecraftforge";
            case "neoforge" -> "net.neoforged";
            case "fabric" -> "net.fabricmc.fabric-loader";
            default -> "org.quiltmc.quilt-loader";
        };
        String mmc = """
                {
                  "formatVersion": 1,
                  "components": [
                    { "uid": "net.minecraft", "version": "%s" },
                    { "uid": "%s", "version": "%s" }
                  ]
                }
                """.formatted(mc, uid, loaderVer);
        Files.writeString(inst.resolve("mmc-pack.json"), mmc);
        return inst;
    }

    private static Path writeJarWithFabricEnv(Path target, String modId, String env, String payload) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry e = new ZipEntry("fabric.mod.json");
            zos.putNextEntry(e);
            String json = """
                    { "schemaVersion": 1, "id": "%s", "version": "%s", "environment": "%s" }
                    """.formatted(modId, payload, env);
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return target;
    }

    // ==========================================================================================
    // Phase 1: Multi-world foundations, PrefixedStorage & independent locks + v1.1 Migration
    // ==========================================================================================

    @Test
    void phase1_twoWorldsHaveIndependentLocksOnSameRepo() throws Exception {
        Path bare = initBareGitRemote();
        Path pcA = tempDir.resolve("pcA");
        Path pcB = tempDir.resolve("pcB");

        Config cfgA = baseGitConfig(pcA, "player_alpha", bare);
        Config cfgB = baseGitConfig(pcB, "player_beta", bare);

        WorldProfile w1A = new WorldProfile("stoneblock3", "StoneBlock 3",
                pcA.resolve("servers/stoneblock3").toString(), "", "", "CREATOR");
        WorldProfile w2B = new WorldProfile("vanilla-plus", "Vanilla Plus",
                pcB.resolve("servers/vanilla-plus").toString(), "", "", "CREATOR");

        Config world1Cfg = cfgA.forWorld(w1A, Map.of());
        Config world2Cfg = cfgB.forWorld(w2B, Map.of());

        try (S3Storage s1 = new S3Storage(world1Cfg);
             S3Storage s2 = new S3Storage(world2Cfg);
             LockManager lm1 = new LockManager(world1Cfg, s1);
             LockManager lm2 = new LockManager(world2Cfg, s2)) {

            assertTrue(lm1.tryAcquire(), "player_alpha should acquire stoneblock3 lock");
            assertEquals(LockManager.State.FREE, lm2.inspect().state(),
                    "vanilla-plus must remain FREE while stoneblock3 is locked");
            assertTrue(lm2.tryAcquire(), "player_beta should acquire vanilla-plus lock simultaneously");

            // PrefixedStorage wrapper also scopes relative keys under worlds/<id>/
            try (S3Storage root = new S3Storage(cfgA.atRepoRoot());
                 PrefixedStorage pref1 = PrefixedStorage.forWorld(root, "stoneblock3");
                 PrefixedStorage pref2 = PrefixedStorage.forWorld(root, "vanilla-plus")) {
                assertTrue(pref1.exists("lock.json"));
                assertTrue(pref2.exists("lock.json"));
            }

            lm1.release();
            lm2.release();
        }
    }

    @Test
    void phase1_v11MigrationAndReversibleRollback() throws Exception {
        Path bare = initBareGitRemote();
        Path pc = tempDir.resolve("pc_mig");
        Config cfgFileModel = baseGitConfig(pc, "player_alpha", bare);
        Path cfgFile = pc.resolve("config.json");
        Json.writeFileAtomic(cfgFile, cfgFileModel);

        try (S3Storage root = new S3Storage(cfgFileModel)) {
            // Seed v1.1 root keys
            ModsManifest legacyMods = new ModsManifest(1, 1700000000L, "player_alpha", Map.of(
                    "mods/create-0.5.1.jar", "aaaa1111",
                    "config/create-common.toml", "bbbb2222"));
            ModsManifest legacyClientMods = new ModsManifest(1, 1700000000L, "player_alpha", Map.of(
                    "mods/create-0.5.1.jar", "aaaa1111",
                    "mods/sodium-0.5.jar", "cccc3333"));
            root.put("mods_manifest.json", Json.toBytes(legacyMods), "application/json");
            root.put("client_mods_manifest.json", Json.toBytes(legacyClientMods), "application/json");
            root.put("modfiles/aaaa1111", "jar-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            root.put("modfiles/bbbb2222", "cfg-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");
            root.put("modfiles/cccc3333", "sodium-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");

            assertTrue(V11Migrator.needsConfigMigration(cfgFileModel));
            assertTrue(V11Migrator.needsStorageMigration(root));

            V11Migrator.MigrationResult res = V11Migrator.migrateIfNeeded(cfgFile, cfgFileModel, root);
            assertTrue(res.configMigrated());
            assertTrue(res.storageMigrated());
            assertEquals("default", res.updatedConfig().activeWorldId());
            assertTrue(res.updatedConfig().findWorld("default").isPresent());

            // Root legacy keys are gone, worlds/default/ has world.json + mods.lock.json, backup exists
            assertFalse(root.exists("mods_manifest.json"));
            assertTrue(root.exists("worlds/default/world.json"));
            assertTrue(root.exists("worlds/default/mods.lock.json"));
            assertTrue(root.exists(V11Migrator.BACKUP_PREFIX + "mods_manifest.json"));

            ModsLock migratedLock = Json.fromBytes(
                    root.get("worlds/default/mods.lock.json").orElseThrow().data(), ModsLock.class);
            assertEquals(ModsLock.SIDE_BOTH, migratedLock.files().get("mods/create-0.5.1.jar").side());
            assertEquals(ModsLock.SIDE_CLIENT, migratedLock.files().get("mods/sodium-0.5.jar").side());
            assertTrue(migratedLock.serverFiles().containsKey("config/create-common.toml"));

            // Reversible rollback restores v1.1 layout
            V11Migrator.rollback(cfgFile, root);
            assertTrue(root.exists("mods_manifest.json"));
            assertFalse(root.exists("worlds/default/mods.lock.json"));
        }
    }

    // ==========================================================================================
    // Phase 2, 3, 4 & 5: Create world, ServerInstaller, Invite (.mcsync), Locked sync, Update & Rollback
    // ==========================================================================================

    @Test
    void phase2to5_fullLifecycle_create_exportImportInvite_hostIdempotent_updateDiffBackupConflictRollback()
            throws Exception {
        Path bare = initBareGitRemote();
        Path hostPc = tempDir.resolve("host_pc");
        Path friendPc = tempDir.resolve("friend_pc");
        Files.createDirectories(hostPc);
        Files.createDirectories(friendPc);

        // Configure Prism data dirs inside each PC's .tools/PrismLauncher
        Files.createDirectories(hostPc.resolve(".tools/PrismLauncher/instances"));
        Files.createDirectories(friendPc.resolve(".tools/PrismLauncher/instances"));

        Config hostCfg = baseGitConfig(hostPc, "player_alpha", bare);
        Path hostCfgFile = hostPc.resolve("config.json");
        Json.writeFileAtomic(hostCfgFile, hostCfg);

        Config friendCfg = baseGitConfig(friendPc, "player_beta", bare);
        Path friendCfgFile = friendPc.resolve("config.json");
        Json.writeFileAtomic(friendCfgFile, friendCfg);

        // Prepare a source Prism instance with 1 shared mod, 1 client-only mod, 1 common config, 1 kubejs script
        Path srcInstance = createPrismInstanceFixture(tempDir, "src-pack", "1.20.1", "fabric", "0.16.0");
        Path srcGame = srcInstance.resolve("minecraft");
        writeJarWithFabricEnv(srcGame.resolve("mods/create-0.5.1.jar"), "create", "*", "0.5.1");
        writeJarWithFabricEnv(srcGame.resolve("mods/sodium-0.5.8.jar"), "sodium", "client", "0.5.8");
        Files.createDirectories(srcGame.resolve("config"));
        Files.writeString(srcGame.resolve("config/create-common.toml"), "speed = 64\n");
        Files.writeString(srcGame.resolve("config/options.txt"), "key_jump:32\n");
        Files.createDirectories(srcGame.resolve("kubejs/server_scripts"));
        Files.writeString(srcGame.resolve("kubejs/server_scripts/recipes.js"), "// server recipe\n");

        // Pre-seed fabric-server-launch.jar in host & friend server folders so ServerInstaller doesn't need internet
        Path hostWorldServer = hostPc.resolve("servers/stoneblock3");
        Files.createDirectories(hostWorldServer);
        Files.writeString(hostWorldServer.resolve("fabric-server-launch.jar"), "fake-fabric-launcher");

        WorldService hostWs = new WorldService(
                hostCfgFile, hostCfg, "git", SourceResolver.NONE, () -> null, msg -> {});

        // 1. Host creates world "stoneblock3"
        CreateWorldRequest createReq = new CreateWorldRequest(
                "stoneblock3",
                "StoneBlock 3 con gli amici",
                new ModpackSource.PrismInstance(srcInstance),
                null,
                2048,
                4096,
                25565,
                List.of("player_alpha", "player_beta"),
                true);
        WorldProfile created = hostWs.create(createReq);
        assertEquals("stoneblock3", created.id());
        assertEquals("mcsync-stoneblock3", created.prismInstanceId());

        // Verify server has create-0.5.1.jar and kubejs, but NOT sodium (client-only!)
        assertTrue(Files.isRegularFile(hostWorldServer.resolve("mods/create-0.5.1.jar")));
        assertFalse(Files.exists(hostWorldServer.resolve("mods/sodium-0.5.8.jar")),
                "Client-only mod must not be placed on the server");
        assertTrue(Files.isRegularFile(hostWorldServer.resolve("kubejs/server_scripts/recipes.js")));
        assertTrue(Files.isRegularFile(hostWorldServer.resolve("config/create-common.toml")));

        // Verify host Prism instance has both create and sodium + pinned common config
        Path hostPrismGame = Path.of(created.clientInstanceDirectory());
        assertTrue(Files.isRegularFile(hostPrismGame.resolve("mods/create-0.5.1.jar")));
        assertTrue(Files.isRegularFile(hostPrismGame.resolve("mods/sodium-0.5.8.jar")));
        assertTrue(Files.isRegularFile(hostPrismGame.resolve("config/create-common.toml")));

        // Phase 2 acceptance: second host start skips all work when recipe+lock hash matches!
        Config hostWorldCfg = hostWs.configFor(created);
        LocalStateStore hostState = new LocalStateStore(hostWorldCfg.stateDir());
        try (S3Storage st = new S3Storage(hostWorldCfg)) {
            WorldStore store = new WorldStore(st);
            WorldRecipe recipe = store.recipe().orElseThrow();
            ModsLock lock = store.lock().orElseThrow().lock();
            LockMaterializer mat = new LockMaterializer(new ModDownloader(new ModCache(hostPc.resolve("cache")), store, ""));
            com.mcsync.world.ServerInstaller srvInst = new com.mcsync.world.ServerInstaller(
                    mat, () -> Path.of("java"),
                    (u, t) -> fail("Fetcher must not be called when recipe hash matches"),
                    (c, d, j) -> {
                        fail("Runner must not be called when recipe hash matches");
                        return 1;
                    });
            com.mcsync.world.ServerInstaller.Result idempotent = srvInst.ensure(recipe, lock, hostWorldServer, hostState);
            assertFalse(idempotent.changed(), "ServerInstaller must skip all work when installedServerRecipeHash matches");
        }

        // 2. Export invite (.mcsync) and import on Friend's PC (Phase 4)
        Path inviteFile = tempDir.resolve("stoneblock3.mcsync");
        hostWs.exportInvite("stoneblock3", inviteFile);
        assertTrue(Files.isRegularFile(inviteFile));

        WorldService friendWs = new WorldService(
                friendCfgFile, friendCfg, "git", SourceResolver.NONE, () -> null, msg -> {});
        WorldProfile imported = friendWs.importInvite(inviteFile);
        assertEquals("stoneblock3", imported.id());
        assertEquals("mcsync-stoneblock3", imported.prismInstanceId());

        // Friend's Prism instance was created and populated from the lockfile!
        Path friendPrismGame = Path.of(imported.clientInstanceDirectory());
        assertTrue(Files.isRegularFile(friendPrismGame.resolve("mods/create-0.5.1.jar")));
        assertTrue(Files.isRegularFile(friendPrismGame.resolve("mods/sodium-0.5.8.jar")));
        assertTrue(Files.isRegularFile(friendPrismGame.resolve("config/create-common.toml")));

        // Live world list shows FREE and v1 applied
        List<WorldService.WorldStatus> statuses = friendWs.listWorlds();
        assertEquals(1, statuses.size());
        assertEquals(WorldService.Live.FREE, statuses.get(0).live());
        assertFalse(statuses.get(0).updateAvailable());
        assertEquals(1L, statuses.get(0).remoteModpackVersion());

        // Phase 3 acceptance: Friend's PC becomes host and builds the server from the recipe automatically!
        Path friendWorldServer = friendPc.resolve("servers/stoneblock3");
        Files.createDirectories(friendWorldServer);
        Files.writeString(friendWorldServer.resolve("fabric-server-launch.jar"), "fake-fabric-launcher");
        Config friendWorldCfg = friendWs.configFor(imported);
        LocalStateStore friendState = new LocalStateStore(friendWorldCfg.stateDir());
        try (S3Storage st = new S3Storage(friendWorldCfg)) {
            ModsSyncManager msm = new ModsSyncManager(friendWorldCfg, st, friendState);
            assertTrue(msm.isLocked());
            msm.syncAsHost();
            assertTrue(msm.lockedStartCommand().isPresent());
            assertTrue(Files.isRegularFile(friendWorldServer.resolve("mods/create-0.5.1.jar")));
            assertFalse(Files.exists(friendWorldServer.resolve("mods/sodium-0.5.8.jar")));

            // Push a world v1 session so pre-update backup has a world to archive
            Files.createDirectories(friendWorldCfg.worldDir());
            Files.writeString(friendWorldCfg.worldDir().resolve("level.dat"), "world-v1-data");
            StorageSyncManager ssm = new StorageSyncManager(friendWorldCfg, st, friendState);
            ssm.pushWorld("sess-1", true);
        }

        // 3. Phase 5: Modpack update is refused while someone is hosting!
        try (S3Storage st = new S3Storage(hostWorldCfg);
             LockManager activeHostLock = new LockManager(hostWorldCfg, st)) {
            assertTrue(activeHostLock.tryAcquire());
            IOException busy = assertThrows(IOException.class, () ->
                    friendWs.updateModpack("stoneblock3", new ModpackSource.PrismInstance(srcInstance),
                            WorldService.UpdateOptions.defaults(), d -> true));
            assertTrue(busy.getMessage().contains("sta ospitando"));
            activeHostLock.release();
        }

        // 4. Phase 5: Friend updates the modpack (upgrade create 0.5.1 -> 0.5.2, add jei, remove sodium)
        Files.deleteIfExists(srcGame.resolve("mods/create-0.5.1.jar"));
        Files.deleteIfExists(srcGame.resolve("mods/sodium-0.5.8.jar"));
        writeJarWithFabricEnv(srcGame.resolve("mods/create-0.5.2.jar"), "create", "*", "0.5.2");
        writeJarWithFabricEnv(srcGame.resolve("mods/jei-15.2.0.jar"), "jei", "*", "15.2.0");

        AtomicReference<LockDiff> capturedDiff = new AtomicReference<>();
        long newVer = friendWs.updateModpack("stoneblock3", new ModpackSource.PrismInstance(srcInstance),
                WorldService.UpdateOptions.defaults(), diff -> {
                    capturedDiff.set(diff);
                    return true;
                });
        assertEquals(2L, newVer);
        assertNotNull(capturedDiff.get());
        assertFalse(capturedDiff.get().updated().isEmpty(), "Diff should detect create 0.5.1 -> 0.5.2 as updated");
        assertFalse(capturedDiff.get().added().isEmpty(), "Diff should detect jei as added");
        assertFalse(capturedDiff.get().removed().isEmpty(), "Diff should detect sodium as removed");

        // Pre-update backup was created
        try (var stream = Files.list(friendWorldCfg.stateDir().resolve("pre-update-backups"))) {
            assertTrue(stream.findAny().isPresent(), "Pre-update world backup must be created");
        }

        // Host now sees "updateAvailable = true" in live status!
        WorldService.WorldStatus hostStatusAfterUpdate = hostWs.status(created);
        assertTrue(hostStatusAfterUpdate.updateAvailable());
        assertEquals(2L, hostStatusAfterUpdate.remoteModpackVersion());

        // Test conditional write conflict (putIfMatch) on WorldStore
        try (S3Storage st = new S3Storage(friendWorldCfg)) {
            WorldStore store = new WorldStore(st);
            WorldStore.LockRef staleRef = new WorldStore.LockRef(
                    store.lock().orElseThrow().lock(), new byte[0], "stale-etag-0000", "0000");
            ModsLock conflicting = staleRef.lock().withVersion(3L, "player_alpha", 1700000100L);
            assertThrows(WorldStore.ConflictException.class,
                    () -> store.update(conflicting, Map.of(), staleRef, null),
                    "Conditional putIfMatch must reject updates based on a stale ETag");
        }

        // 5. Phase 5: Rollback to v1 in one click!
        assertEquals(List.of(1L, 2L), friendWs.modpackVersions("stoneblock3"));
        long rolledVer = friendWs.rollback("stoneblock3", 1L);
        assertEquals(3L, rolledVer);
        assertTrue(Files.isRegularFile(friendPrismGame.resolve("mods/create-0.5.1.jar")),
                "Rollback to v1 must restore create-0.5.1.jar");
        assertFalse(Files.exists(friendPrismGame.resolve("mods/jei-15.2.0.jar")),
                "Rollback to v1 must remove jei-15.2.0.jar added in v2");
    }

    // ==========================================================================================
    // Phase 6: ZipClassifier, PrismInstances, DiscordNotifier world label & world-aware LogAnalyzer
    // ==========================================================================================

    @Test
    void phase6_zipClassifierPrismDiscordAndDiagnostics() throws Exception {
        // 1. ZipClassifier distinguishes .mrpack, CurseForge client zip, and Server Pack zip
        Path cfZip = tempDir.resolve("client-pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(cfZip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("""
                    {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.3.0","primary":true}]},"files":[]}
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("overrides/config/test.toml"));
            zos.write("a=1".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertEquals(ZipClassifier.Kind.CURSEFORGE_CLIENT, ZipClassifier.classify(cfZip).kind());

        Path srvZip = tempDir.resolve("server-pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(srvZip))) {
            zos.putNextEntry(new ZipEntry("ServerPack/run.sh"));
            zos.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("ServerPack/mods/modA.jar"));
            zos.write("jar".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertEquals(ZipClassifier.Kind.SERVER_PACK, ZipClassifier.classify(srvZip).kind());

        // 2. PrismInstances.ensureWorldInstance creates mcsync-<worldId> from lock metadata alone
        Path base = tempDir.resolve("prism-host");
        Files.createDirectories(base.resolve(".tools/PrismLauncher/instances"));
        Path gameDir = PrismInstances.ensureWorldInstance(
                base, "stoneblock3", "StoneBlock 3", "1.20.1", new ModsLock.Loader("forge", "47.3.0"), 2048, 4096);
        assertTrue(Files.isRegularFile(gameDir.getParent().resolve("instance.cfg")));
        assertTrue(Files.isRegularFile(gameDir.getParent().resolve("mmc-pack.json")));
        assertEquals("StoneBlock 3", PrismInstances.instanceName(gameDir));

        // 3. DiscordNotifier includes the active world name
        Path bare = initBareGitRemote();
        Config cfg = baseGitConfig(tempDir.resolve("disc"), "player_alpha", bare)
                .withWorld(new WorldProfile("stoneblock3", "StoneBlock 3",
                        tempDir.resolve("disc/srv").toString(), "mcsync-stoneblock3", "", "CREATOR"))
                .withActiveWorld("stoneblock3");
        DiscordNotifier notifier = new DiscordNotifier(cfg);
        assertEquals("StoneBlock 3 (stoneblock3)", notifier.worldLabel());

        // 4. World-aware LogAnalyzer
        List<LogAnalyzer.Diagnosis> diags = LogAnalyzer.analyze(
                List.of("player_alpha sta ospitando il mondo: riprova a fine sessione"), "StoneBlock 3");
        assertEquals(1, diags.size());
        assertTrue(diags.get(0).title().startsWith("[StoneBlock 3] "));
    }
}
