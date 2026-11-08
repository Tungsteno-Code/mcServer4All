package com.mcsync;

import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.core.ModsSyncManager;
import com.mcsync.core.ServerProcessController;
import com.mcsync.core.StorageSyncManager;
import com.mcsync.model.LockFile;
import com.mcsync.model.ModsManifest;
import com.mcsync.model.WorldManifest;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.diagnostics.LogAnalyzer;
import com.mcsync.setup.DependencyInstaller;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Glob;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import com.mcsync.util.ProcessUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McSyncLauncherTest {

    @Test
    void archiveAndExtractRoundTripWithSha256(@TempDir Path tempDir) throws Exception {
        Path world = tempDir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("datapacks/custom"));
        Files.writeString(world.resolve("level.dat"), "level-data-content");
        Files.writeString(world.resolve("region/r.0.0.mca"), "region-chunk-bytes-1234567890");
        Files.writeString(world.resolve("datapacks/custom/pack.mcmeta"), "{\"pack\":{\"pack_format\":15}}");
        // session.lock must be ignored during archiving
        Files.writeString(world.resolve("session.lock"), "locked");

        Path archiveFile = tempDir.resolve("world_latest.tar.zst");
        StorageSyncManager.ArchiveResult res = StorageSyncManager.archive(world, archiveFile);

        assertTrue(Files.isRegularFile(archiveFile));
        assertTrue(res.sizeBytes() > 0);
        assertEquals(res.sha256(), StorageSyncManager.sha256(archiveFile));

        Path extracted = tempDir.resolve("extracted");
        StorageSyncManager.extract(archiveFile, extracted);

        assertEquals("level-data-content", Files.readString(extracted.resolve("level.dat")));
        assertEquals("region-chunk-bytes-1234567890", Files.readString(extracted.resolve("region/r.0.0.mca")));
        assertEquals("{\"pack\":{\"pack_format\":15}}", Files.readString(extracted.resolve("datapacks/custom/pack.mcmeta")));
        assertFalse(Files.exists(extracted.resolve("session.lock")));
    }

    @Test
    void modsManifestScanAndDiff(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(tempDir.resolve("mods/create.jar"), "jar-bytes-1");
        Files.writeString(tempDir.resolve("mods/sodium-client.jar"), "client-only");
        Files.writeString(tempDir.resolve("config/create-server.toml"), "toml-content");
        Files.writeString(tempDir.resolve("mods/readme.txt"), "ignored-extension");

        Config cfg = sampleConfig(tempDir).withLocalTailscaleIp("100.64.0.1");
        ModsSyncManager mgr = new ModsSyncManager(cfg, null, null);
        ModsManifest local = mgr.scan(tempDir);

        assertEquals(2, local.files().size());
        assertTrue(local.files().containsKey("mods/create.jar"));
        assertTrue(local.files().containsKey("config/create-server.toml"));
        assertFalse(local.files().containsKey("mods/sodium-client.jar"));

        ModsManifest remote = new ModsManifest(1, 1700000000L, "player_beta", Map.of(
                "mods/create.jar", "different_hash",
                "mods/jei.jar", "new_mod_hash"
        ));

        ModsSyncManager.Diff diff = ModsSyncManager.diff(local, remote);
        assertFalse(diff.isEmpty());
        assertTrue(diff.missingLocally().containsKey("mods/jei.jar"));
        assertTrue(diff.changed().containsKey("mods/create.jar"));
        assertTrue(diff.extraLocally().containsKey("config/create-server.toml"));
    }

    @Test
    void serverPropertiesEnforcementAndConsoleParsing(@TempDir Path tempDir) throws Exception {
        Path props = tempDir.resolve("server.properties");
        Files.writeString(props, "motd=My Modded Server\nserver-ip=0.0.0.0\nonline-mode=false\n", StandardCharsets.ISO_8859_1);

        List<String> joined = new ArrayList<>();
        List<String> left = new ArrayList<>();
        AtomicBoolean oom = new AtomicBoolean(false);
        AtomicReference<String> watchdog = new AtomicReference<>();

        Config cfg = sampleConfig(tempDir).withLocalTailscaleIp("100.90.1.2");
        ServerProcessController ctrl = new ServerProcessController(cfg, new ServerProcessController.Listener() {
            @Override
            public void onPlayerJoined(String name, int online) {
                joined.add(name);
            }

            @Override
            public void onPlayerLeft(String name, int online) {
                left.add(name);
            }

            @Override
            public void onOutOfMemory() {
                oom.set(true);
            }

            @Override
            public void onTickWatchdog(String seconds) {
                watchdog.set(seconds);
            }
        });

        ctrl.prepareServerProperties();
        String updated = Files.readString(props, StandardCharsets.ISO_8859_1);
        assertTrue(updated.contains("server-ip=100.90.1.2"));
        assertTrue(updated.contains("online-mode=true"));
        assertTrue(updated.contains("white-list=true"));
        assertTrue(updated.contains("motd=My Modded Server"));
        assertTrue(Files.exists(tempDir.resolve("eula.txt")));

        ctrl.handleLine("[12:00:00] [Server thread/INFO]: Done (14.231s)! For help, type \"help\"");
        assertTrue(ctrl.isReady());

        ctrl.handleLine("[12:01:00] [Server thread/INFO]: player_alpha joined the game");
        assertEquals(List.of("player_alpha"), joined);
        assertTrue(ctrl.onlinePlayers().contains("player_alpha"));

        ctrl.handleLine("[12:02:00] [Server thread/INFO]: player_alpha left the game");
        assertEquals(List.of("player_alpha"), left);
        assertTrue(ctrl.onlinePlayers().isEmpty());

        ctrl.handleLine("[12:03:00] [Server Watchdog/WARN]: A single server tick took 60.00 seconds (should be max 0.05)");
        assertEquals("60.00", watchdog.get());

        ctrl.handleLine("java.lang.OutOfMemoryError: Java heap space");
        assertTrue(oom.get());
        ctrl.shutdownMonitor();
    }

    @Test
    void modelsJsonSerializationAndWindowsCommandPrep(@TempDir Path tempDir) throws Exception {
        LockFile lock = new LockFile("player_alpha", "100.64.0.1", 1700000030L, "uuid-1", LockFile.Status.ONLINE, 25565, 1700000000L);
        LockFile parsed = Json.fromBytes(Json.toBytes(lock), LockFile.class);
        assertEquals("player_alpha", parsed.ownerId());
        assertEquals("100.64.0.1", parsed.address());

        WorldManifest wm = new WorldManifest(1, 3, 1700000030L, "abc", 1024L, "world_latest.tar.zst",
                "backups/world_v3_1700000030.tar.zst", "player_alpha", "uuid-1", true, "world");
        WorldManifest parsedWm = Json.fromBytes(Json.toBytes(wm), WorldManifest.class);
        assertEquals(3, parsedWm.version());

        List<String> cmd = ProcessUtils.prepareCommand(
                List.of("prism.exe", "--server", "{serverAddress}", "--user", "{playerId}"),
                Map.of("serverAddress", "100.64.0.1", "playerId", "player_alpha"));
        assertEquals(List.of("prism.exe", "--server", "100.64.0.1", "--user", "player_alpha"), cmd);

        Glob glob = new Glob(List.of("mods/sodium*"));
        assertTrue(glob.matches("mods/sodium-fabric-0.5.8.jar"));
        assertFalse(glob.matches("mods/create.jar"));
        assertEquals("a/b/c.jar", FileUtils.toPortable(Path.of("a", "b", "c.jar")));

        // Verify CurseForge server pack preparation (stripping 'pause' + auto-detecting Forge args)
        Path runBat = tempDir.resolve("run.bat");
        Files.writeString(runBat, "@echo off\njava @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1/win_args.txt %*\npause\n");
        Path argsDir = tempDir.resolve("libraries/net/minecraftforge/forge/1.20.1");
        Files.createDirectories(argsDir);
        Files.writeString(argsDir.resolve("win_args.txt"), "-cp server.jar");
        Files.writeString(argsDir.resolve("unix_args.txt"), "-cp server.jar");
        Files.writeString(tempDir.resolve("user_jvm_args.txt"), "-Xmx6G");

        DependencyInstaller installer = new DependencyInstaller(tempDir);
        Config updated = installer.prepareServerPack(sampleConfig(tempDir));
        String sanitizedBat = Files.readString(runBat);
        assertFalse(sanitizedBat.lines().anyMatch(l -> l.trim().equalsIgnoreCase("pause")));
        assertTrue(updated.serverStartCommand().stream().anyMatch(s -> s.startsWith("@libraries/")));
    }

    @Test
    void gitBackendAtomicLockAndSessionDeltaSync(@TempDir Path tempDir) throws Exception {
        // 1. Create a real bare Git repository simulating a private GitHub repo
        Path bareRepo = tempDir.resolve("remote-repo.git");
        Files.createDirectories(bareRepo);
        int initExit = new ProcessBuilder("git", "init", "--bare", bareRepo.toString()).start().waitFor();
        assertEquals(0, initExit);

        Path pcAlpha = tempDir.resolve("pc_alpha");
        Path pcBeta = tempDir.resolve("pc_beta");
        Files.createDirectories(pcAlpha.resolve("world/region"));
        Files.createDirectories(pcAlpha.resolve("world/playerdata"));
        Files.createDirectories(pcBeta);

        Config cfgAlpha = gitConfig("player_alpha", "100.64.0.1", bareRepo.toString(), pcAlpha);
        Config cfgBeta = gitConfig("player_beta", "100.64.0.2", bareRepo.toString(), pcBeta);

        try (S3Storage storageAlpha = new S3Storage(cfgAlpha);
             S3Storage storageBeta = new S3Storage(cfgBeta);
             LockManager lockAlpha = new LockManager(cfgAlpha, storageAlpha);
             LockManager lockBeta = new LockManager(cfgBeta, storageBeta)) {

            LocalStateStore stateAlpha = new LocalStateStore(cfgAlpha.stateDir());
            LocalStateStore stateBeta = new LocalStateStore(cfgBeta.stateDir());
            StorageSyncManager syncAlpha = new StorageSyncManager(cfgAlpha, storageAlpha, stateAlpha);
            StorageSyncManager syncBeta = new StorageSyncManager(cfgBeta, storageBeta, stateBeta);

            // 2. Test Atomic Lock over Git: Alpha acquires, Beta is rejected
            assertTrue(lockAlpha.tryAcquire(), "Alpha should acquire the lock on empty repo");
            assertFalse(lockBeta.tryAcquire(), "Beta must be rejected while Alpha holds the lock");
            assertEquals(LockManager.State.HELD, lockBeta.inspect().state());

            // 3. Session 1 (Alpha): creates initial world with 10 region files
            Files.writeString(pcAlpha.resolve("world/level.dat"), "seed=12345;time=1000");
            for (int i = 0; i < 10; i++) {
                Files.writeString(pcAlpha.resolve("world/region/r.0." + i + ".mca"),
                        "INITIAL_REGION_DATA_" + i + "_".repeat(200));
            }
            Files.writeString(pcAlpha.resolve("world/playerdata/alpha.dat"), "alpha_inv_v1");

            StorageSyncManager.PushResult push1 = syncAlpha.pushWorld(lockAlpha.sessionId(), true);
            assertEquals(1L, push1.manifest().version());
            assertEquals(12, push1.manifest().worldFiles().size());
            lockAlpha.release();
            assertEquals(LockManager.State.FREE, lockBeta.inspect().state());

            // 4. Session 2 (Beta): acquires lock, pulls v1, modifies ONLY 1 region + adds beta.dat + deletes r.0.9.mca
            assertTrue(lockBeta.tryAcquire(), "Beta should acquire the lock after Alpha released");
            WorldManifest remoteV1 = syncBeta.fetchRemoteManifest().orElseThrow();
            syncBeta.pullWorld(remoteV1);

            assertEquals("seed=12345;time=1000", Files.readString(pcBeta.resolve("world/level.dat")));
            assertEquals("INITIAL_REGION_DATA_0" + "_".repeat(200),
                    Files.readString(pcBeta.resolve("world/region/r.0.0.mca")));

            // Beta modifies ONLY r.0.2.mca and level.dat, adds beta.dat, and deletes r.0.9.mca
            String r02Modified = "MODIFIED_IN_SESSION_2_BY_BETA_" + "_".repeat(200);
            Files.writeString(pcBeta.resolve("world/region/r.0.2.mca"), r02Modified);
            Files.writeString(pcBeta.resolve("world/level.dat"), "seed=12345;time=24000");
            Files.writeString(pcBeta.resolve("world/playerdata/beta.dat"), "beta_inv_v2");
            Files.delete(pcBeta.resolve("world/region/r.0.9.mca"));

            StorageSyncManager.PushResult push2 = syncBeta.pushWorld(lockBeta.sessionId(), true);
            assertEquals(2L, push2.manifest().version());
            assertEquals(List.of("region/r.0.9.mca"), push2.manifest().deletedFiles());
            // The delta size pushed in Session 2 must be much smaller than pushing all 10 regions
            assertTrue(push2.archiveBytes() < push1.archiveBytes(),
                    "Session 2 delta (" + push2.archiveBytes() + " B) must be smaller than initial world (" + push1.archiveBytes() + " B)");
            lockBeta.release();

            // 5. Session 3 (Alpha): pulls v2 differential update onto its existing v1 world
            WorldManifest remoteV2 = syncAlpha.fetchRemoteManifest().orElseThrow();
            FileTime unchangedMtimeBefore = Files.getLastModifiedTime(pcAlpha.resolve("world/region/r.0.0.mca"));
            syncAlpha.pullWorld(remoteV2);
            FileTime unchangedMtimeAfter = Files.getLastModifiedTime(pcAlpha.resolve("world/region/r.0.0.mca"));

            // Verify unchanged region r.0.0.mca was NOT touched/rewritten on Alpha's disk!
            assertEquals(unchangedMtimeBefore, unchangedMtimeAfter,
                    "Unchanged region r.0.0.mca must not be rewritten during differential pull");
            // Verify modified files & deleted files were applied
            assertEquals(r02Modified, Files.readString(pcAlpha.resolve("world/region/r.0.2.mca")));
            assertEquals("seed=12345;time=24000", Files.readString(pcAlpha.resolve("world/level.dat")));
            assertEquals("beta_inv_v2", Files.readString(pcAlpha.resolve("world/playerdata/beta.dat")));
            assertFalse(Files.exists(pcAlpha.resolve("world/region/r.0.9.mca")),
                    "Deleted region r.0.9.mca must be removed on Alpha");

            // 6. Verify the remote bare Git repository has EXACTLY 1 commit on mcsync-state (zero history bloat!)
            Process revList = new ProcessBuilder("git", "-C", bareRepo.toString(), "rev-list", "--count", "mcsync-state")
                    .redirectErrorStream(true)
                    .start();
            String commitCount = new String(revList.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, revList.waitFor());
            assertEquals("1", commitCount, "Remote Git branch must always have exactly 1 commit (no history bloat)");
        }
    }

    @Test
    void clientSyncScopeModsOnlySkipsConfigsAndUsesQuickCache(@TempDir Path tempDir) throws Exception {
        Path bareRepo = tempDir.resolve("mods-remote.git");
        assertEquals(0, new ProcessBuilder("git", "init", "--bare", bareRepo.toString()).start().waitFor());

        Path hostDir = tempDir.resolve("host");
        Path clientDir = tempDir.resolve("client");
        Files.createDirectories(hostDir.resolve("mods"));
        Files.createDirectories(hostDir.resolve("config"));
        Files.createDirectories(clientDir.resolve("mods"));
        Files.createDirectories(clientDir.resolve("config"));

        Files.writeString(hostDir.resolve("mods/create.jar"), "create-jar-v1");
        Files.writeString(hostDir.resolve("config/create-server.toml"), "server-only-toml");
        Files.writeString(clientDir.resolve("config/options-custom.toml"), "player-custom-config");

        Config hostCfg = gitConfig("host_p", "100.64.0.1", bareRepo.toString(), hostDir);
        Config clientCfg = gitConfig("client_p", "100.64.0.2", bareRepo.toString(), clientDir);
        assertTrue(clientCfg.clientModsOnly(), "Default clientSyncScope must be MODS_ONLY");

        try (S3Storage hostStorage = new S3Storage(hostCfg);
             S3Storage clientStorage = new S3Storage(clientCfg)) {
            LocalStateStore hostState = new LocalStateStore(hostCfg.stateDir());
            LocalStateStore clientState = new LocalStateStore(clientCfg.stateDir());
            ModsSyncManager hostMods = new ModsSyncManager(hostCfg, hostStorage, hostState);
            ModsSyncManager clientMods = new ModsSyncManager(clientCfg, clientStorage, clientState);

            hostMods.syncAsHost();
            clientMods.syncAsClient();

            // Client must have downloaded mods/create.jar, but NOT config/create-server.toml
            assertTrue(Files.isRegularFile(clientDir.resolve("mods/create.jar")));
            assertFalse(Files.exists(clientDir.resolve("config/create-server.toml")),
                    "MODS_ONLY client sync must not download .toml config files");
            assertTrue(Files.isRegularFile(clientDir.resolve("config/options-custom.toml")),
                    "Local client configs must remain untouched");

            // Quick-Hash Cache must be populated in .mcsync/state.json
            assertFalse(clientState.get().quickSyncCache().isEmpty(),
                    "Quick-Hash cache must store remote manifest fingerprint");
            assertFalse(clientMods.hasPendingUpdates(), "No pending updates right after sync");
        }
    }

    @Test
    void tailscaleLoopbackAddressResolutionPreventsGetsockoptTimeout(@TempDir Path tempDir) {
        Config cfg = sampleConfig(tempDir);
        // sampleConfig has localTailscaleIp="100.64.0.1" and localClientAddress="127.0.0.1" with serverBindAll=false
        assertEquals("100.64.0.1", cfg.effectiveClientAddress(),
                "When server binds to Tailscale IP, localClientAddress=127.0.0.1 must auto-align to Tailscale IP");
        assertEquals("100.64.0.1", cfg.serverBindAddress());

        Config bindAll = cfg.with(Map.of("serverBindAll", true, "localClientAddress", "127.0.0.1"));
        assertEquals("", bindAll.serverBindAddress());
        assertEquals("127.0.0.1", bindAll.effectiveClientAddress());
    }

    @Test
    void serverProfileDetectorAndPreflightCommandRepair(@TempDir Path tempDir) throws Exception {
        String runScript = ServerProfileDetector.runScriptName();
        Files.writeString(tempDir.resolve(runScript), "echo starting");
        Files.writeString(tempDir.resolve("user_jvm_args.txt"), "-Xms4G -Xmx6G");
        Files.createDirectories(tempDir.resolve("libraries"));

        ServerProfileDetector.Detection d = ServerProfileDetector.detect(tempDir);
        assertEquals(ServerProfileDetector.Profile.FORGE_MODERN, d.profile());
        assertEquals(List.of(runScript, "nogui"), d.suggestedCommand());

        // Repair "java -jar run.bat" -> ["run.bat", "nogui"]
        List<String> repaired = ServerProfileDetector.repairCommand(
                List.of("java", "-Xmx6G", "-jar", runScript), tempDir);
        assertEquals(List.of(runScript, "nogui"), repaired);
    }

    @Test
    void logAnalyzerAndConfigFixerAutoRemediation(@TempDir Path tempDir) throws Exception {
        List<String> logs = List.of(
                "[ERROR] io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection timed out: getsockopt: /127.0.0.1:25565",
                "[server] Error: Invalid or corrupt jarfile run.bat",
                "[server] Errore: impossibile trovare o caricare la classe principale @user_jvm_args.txt"
        );
        List<LogAnalyzer.Diagnosis> diags = LogAnalyzer.analyze(logs);
        assertEquals(3, diags.size());
        assertEquals(LogAnalyzer.FixAction.ALIGN_CLIENT_IP, diags.get(0).fix());
        assertEquals(LogAnalyzer.FixAction.FIX_RUN_SCRIPT, diags.get(1).fix());
        assertEquals(LogAnalyzer.FixAction.USE_JAVA21_FOR_SERVER, diags.get(2).fix());

        Path cfgFile = tempDir.resolve("config.json");
        MainOrchestrator.initConfig(cfgFile);
        ConfigFixer fixer = new ConfigFixer(cfgFile);
        fixer.apply(LogAnalyzer.FixAction.ALIGN_CLIENT_IP, null);
        assertEquals("auto", fixer.read().get("localClientAddress").asText());
    }

    private static Config gitConfig(String playerId, String ip, String gitRemote, Path serverDir) {
        return new Config(
                playerId,
                ip,
                "GIT",
                gitRemote,
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
                30,
                120,
                5,
                25565,
                true,
                "",
                List.of(playerId),
                true,
                60,
                15,
                900,
                900,
                60,
                900,
                "127.0.0.1",
                "DIFFERENTIAL",
                "",
                null,
                List.of(),
                false
        );
    }

    private static Config sampleConfig(Path serverDir) {
        return new Config(
                "player_alpha",
                "100.64.0.1",
                "https://example.r2.cloudflarestorage.com",
                "auto",
                "mc-bucket",
                "key",
                "secret",
                serverDir.toString(),
                List.of("java", "-jar", "server.jar", "nogui"),
                "world",
                List.of("prismlauncher", "--launch", "Pack", "--server", "{serverAddress}"),
                "",
                30,
                120,
                5,
                25565,
                true,
                "",
                List.of("player_alpha"),
                true,
                60,
                15,
                900,
                900,
                60,
                900,
                "127.0.0.1",
                "DIFFERENTIAL",
                "",
                null,
                List.of("mods/sodium*"),
                false
        );
    }
}
