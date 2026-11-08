package com.mcsync;

import com.mcsync.config.Config;
import com.mcsync.core.ClientLauncher;
import com.mcsync.core.LockManager;
import com.mcsync.core.ModsSyncManager;
import com.mcsync.core.ServerProcessController;
import com.mcsync.core.StorageSyncManager;
import com.mcsync.model.LocalState;
import com.mcsync.model.LockFile;
import com.mcsync.model.WorldManifest;
import com.mcsync.notify.DiscordNotifier;
import com.mcsync.setup.DependencyInstaller;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.LocalStateStore;
import com.mcsync.util.NetUtils;
import com.mcsync.util.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point and orchestration state machine of {@code mc-sync-launcher}.
 *
 * <pre>
 * CHECKING_LOCK --free/stale--> ACQUIRING_LOCK -> SYNCING_DOWN -> SYNCING_MODS -> STARTING_SERVER
 *       |                                                                            |
 *       +--held--> WAITING_FOR_HOST -> CLIENT_MODE -> TERMINATED                     v
 *                                       HOSTING -> DAEMON_HOSTING -> SHUTTING_DOWN -> SYNCING_UP
 *                                                                   -> RELEASING_LOCK -> TERMINATED
 * </pre>
 *
 * A JVM shutdown hook guarantees that, whenever possible, the server is stopped, the world is
 * uploaded and the lock released even if the console is closed with Ctrl+C.
 */
public final class MainOrchestrator implements AutoCloseable {

    public static final String VERSION = Optional.ofNullable(MainOrchestrator.class.getPackage().getImplementationVersion())
            .orElse("dev");

    private static Logger log;

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_CONFIG = 2;

    private volatile Config cfg;
    private final boolean launchClient;
    private final S3Storage storage;
    private final LocalStateStore localState;
    private final LockManager lockManager;
    private final StorageSyncManager worldSync;
    private final ModsSyncManager modsSync;
    private final DiscordNotifier discord;
    private final ClientLauncher clientLauncher;

    private volatile OrchestratorState state = OrchestratorState.INITIALIZING;
    private volatile ServerProcessController server;
    private volatile com.mcsync.core.IntegratedHostController integratedHost;
    private volatile boolean lockLost;
    private volatile boolean serverCrashed;
    private final CompletableFuture<String> shutdownSignal = new CompletableFuture<>();
    private volatile Observer observer = new Observer() {
    };
    private volatile boolean consoleInputEnabled = true;
    private volatile LockFile currentHost;

    /** Live events for the GUI. All callbacks are invoked from background threads. */
    public interface Observer {
        default void stateChanged(OrchestratorState state) {
        }

        default void serverLine(String line) {
        }

        default void playersChanged(Set<String> players) {
        }

        /** Called when this PC joins someone else's server (lock = remote host). */
        default void joinedHost(LockFile lock) {
        }
    }

    /** Result of {@link #bootstrap}: resolved configuration and git executable. */
    public record Prepared(Config cfg, String gitExecutable) {
    }

    /** Read-only overview used by {@code status} and the GUI dashboard. */
    public record Snapshot(LockManager.Inspection lock, Optional<WorldManifest> remoteWorld, LocalState local,
                           int backups, boolean modUpdatesPending) {
    }
    private final AtomicBoolean teardownDone = new AtomicBoolean(false);
    private final Object teardownMonitor = new Object();

    public MainOrchestrator(Config cfg, boolean launchClient) {
        this(cfg, launchClient, "git");
    }

    public MainOrchestrator(Config cfg, boolean launchClient, String gitExecutable) {
        this.cfg = cfg;
        this.launchClient = launchClient;
        this.storage = new S3Storage(cfg, gitExecutable);
        this.localState = new LocalStateStore(cfg.stateDir());
        this.lockManager = new LockManager(cfg, storage);
        this.worldSync = new StorageSyncManager(cfg, storage, localState);
        this.modsSync = new ModsSyncManager(cfg, storage, localState);
        this.discord = new DiscordNotifier(cfg);
        this.clientLauncher = new ClientLauncher(cfg);
    }

    // ==========================================================================================
    // main / CLI
    // ==========================================================================================

    public static void main(String[] args) {
        List<String> argList = new ArrayList<>(Arrays.asList(args));
        boolean debug = argList.remove("--debug");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", debug ? "debug" : "info");
        log = LoggerFactory.getLogger(MainOrchestrator.class);

        boolean assumeYes = argList.remove("--yes") | argList.remove("-y");
        boolean noClient = argList.remove("--no-client") | argList.remove("--host-only");
        boolean noGui = argList.remove("--no-gui") | argList.remove("--nogui") | argList.remove("--cli");
        boolean forceGui = argList.remove("--gui");
        Path configPath = null;
        int idx = argList.indexOf("--config");
        if (idx < 0) idx = argList.indexOf("-c");
        if (idx >= 0) {
            if (idx + 1 >= argList.size()) {
                System.err.println("--config requires a path");
                System.exit(EXIT_CONFIG);
            }
            configPath = Path.of(argList.get(idx + 1));
            argList.remove(idx + 1);
            argList.remove(idx);
        }
        boolean noCommand = argList.isEmpty();
        String command = noCommand ? "run" : argList.remove(0).toLowerCase(Locale.ROOT);

        if ((noCommand && !noGui || forceGui) && !java.awt.GraphicsEnvironment.isHeadless()) {
            com.mcsync.gui.GuiLauncher.launch(configPath != null ? configPath : defaultConfigPath(), debug);
            return;
        }

        switch (command) {
            case "help", "--help", "-h", "/?" -> {
                printHelp();
                System.exit(EXIT_OK);
            }
            case "version", "--version", "-v" -> {
                System.out.println("mcServer4all " + VERSION);
                System.exit(EXIT_OK);
            }
            case "init" -> System.exit(initConfig(configPath != null ? configPath : defaultConfigPath()));
            default -> {
                // continue
            }
        }

        Path cfgFile = configPath != null ? configPath : defaultConfigPath();
        if ("run".equals(command) && !Files.exists(cfgFile)) {
            initConfig(cfgFile);
            System.out.println("Fill in " + cfgFile.toAbsolutePath() + " (or start without --no-gui to use the "
                    + "graphical configuration editor) and launch again.");
            System.exit(EXIT_CONFIG);
        }
        DependencyInstaller installer = new DependencyInstaller(
                cfgFile.toAbsolutePath().getParent() != null ? cfgFile.toAbsolutePath().getParent() : Path.of("."));

        if ("setup".equals(command)) {
            try {
                installer.ensureJava21();
                installer.ensureTailscaleInstalled();
                if (FileUtils.IS_WINDOWS) installer.ensureWindowsFirewallRule(25565);
                if (Files.exists(cfgFile)) {
                    Config loaded = Config.load(cfgFile);
                    installer.ensureAll(loaded, !noClient);
                } else {
                    initConfig(cfgFile);
                }
                log.info("Setup e verifica dipendenze completati.");
                System.exit(EXIT_OK);
            } catch (Exception e) {
                log.error("Setup fallito: {}", e.getMessage());
                System.exit(EXIT_ERROR);
            }
        }

        Config cfg;
        try {
            cfg = Config.load(cfgFile);
            if ("run".equals(command) || "import-curseforge".equals(command)) {
                cfg = installer.ensureAll(cfg, !noClient);
            }
            if ("import-curseforge".equals(command)) {
                if (argList.isEmpty()) {
                    log.error("Uso: launch.bat import-curseforge <percorso-modpack-o-serverpack.zip>");
                    System.exit(EXIT_CONFIG);
                }
                installer.importCurseForgeZip(cfg, Path.of(argList.get(0)));
                System.exit(EXIT_OK);
            }
            if ("world".equals(command)) {
                int wCode = com.mcsync.world.WorldCli.handleWorldCommand(cfgFile, cfg, installer, argList, assumeYes);
                System.exit(wCode);
            }
            if ("modpack".equals(command)) {
                int mCode = com.mcsync.world.WorldCli.handleModpackCommand(cfgFile, cfg, installer, argList, assumeYes);
                System.exit(mCode);
            }
            if ("migrate".equals(command) || "migrate-rollback".equals(command)) {
                int migCode = com.mcsync.world.WorldCli.handleMigrateCommand(
                        cfgFile, cfg, installer, "migrate-rollback".equals(command));
                System.exit(migCode);
            }
            if ("join-file".equals(command)) {
                if (argList.isEmpty()) {
                    log.error("Uso: launch.bat join-file <invito.mcsync>");
                    System.exit(EXIT_CONFIG);
                }
                com.mcsync.world.WorldService svc = com.mcsync.world.WorldCli.newService(cfgFile, cfg, installer);
                com.mcsync.model.WorldProfile imported = svc.importInvite(Path.of(argList.remove(0)));
                cfg = svc.config();
                log.info("Invito importato: mondo '{}' registrato e selezionato.", imported.displayName());
                if (!argList.remove("--run")) {
                    System.exit(EXIT_OK);
                }
                command = "run";
            }
            if ("join".equals(command)) {
                if (!argList.isEmpty()) {
                    String wid = argList.remove(0);
                    com.mcsync.world.WorldService svc = com.mcsync.world.WorldCli.newService(cfgFile, cfg, installer);
                    svc.setActiveWorld(wid);
                    cfg = svc.config();
                }
                command = "run";
            }
            if (cfg.activeWorld().isPresent()) {
                com.mcsync.world.WorldService svc = com.mcsync.world.WorldCli.newService(cfgFile, cfg, installer);
                cfg = svc.configFor(cfg.activeWorld().get());
            }
            cfg = resolveTailscaleIp(cfg);
            logClientAddressResolution(cfg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error(e.getMessage());
            System.exit(EXIT_CONFIG);
            return;
        } catch (IOException e) {
            log.error("Cannot read configuration: {}", e.getMessage());
            System.exit(EXIT_CONFIG);
            return;
        }
        enableFileLog(cfg);

        int code;
        String gitExe = cfg.isGitBackend() ? installer.ensureGitInstalled() : "git";
        MainOrchestrator orchestrator = new MainOrchestrator(cfg, !noClient, gitExe);
        try {
            code = switch (command) {
                case "run" -> orchestrator.run();
                case "status" -> orchestrator.printStatus();
                case "force-unlock" -> orchestrator.forceUnlock(assumeYes);
                case "list-backups" -> orchestrator.listBackups();
                case "restore" -> orchestrator.restore(argList.isEmpty() ? null : argList.get(0), assumeYes);
                case "push-mods" -> orchestrator.pushMods();
                case "pull-mods" -> orchestrator.pullMods();
                default -> {
                    log.error("Unknown command '{}'", command);
                    printHelp();
                    yield EXIT_CONFIG;
                }
            };
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), debug ? e : null);
            code = EXIT_ERROR;
        } finally {
            orchestrator.close();
        }
        System.exit(code);
    }

    private static void printHelp() {
        System.out.println("""
                mcServer4all %s

                Usage: launch.bat [command] [options]      (Linux/macOS: ./launch.sh)

                Commands:
                  run                        (default) host or join the active world automatically
                  join <worldId>             select <worldId> and start (host if free, join as client if online)
                  join-file <invito.mcsync>  import a .mcsync invite file (registers world, downloads mods, creates Prism instance)
                  world create <id> ...      create a new locked-modpack world (--client-pack <zip/mrpack/instance>)
                  world list                 list registered worlds with live status
                  world export <id> [file]   export a .mcsync invite file for friends
                  world import <file.mcsync> import a .mcsync invite file
                  modpack update [id] <pack> update a world's locked modpack (with world lock, diff, backup & putIfMatch)
                  modpack rollback [id] [v]  roll back a world's locked modpack to a previous version
                  setup                      auto-install Java 21, Prism Launcher, Tailscale & Firewall rules
                  import-curseforge <zip>    install a CurseForge Server Pack or client Modpack .zip
                  status                     show lock, remote world and local state
                  list-backups               list remote world backups
                  restore <key>              make a remote backup the current world (no-one must be hosting)
                  force-unlock               delete the remote lock (only if you are sure nobody is hosting)
                  push-mods                  publish local mods/configs as the reference modpack
                  pull-mods                  download the reference modpack
                  init                       create config.json from the template
                  gui                        open the graphical interface (same as no command)
                  version | help

                Options:
                  --config <file>  configuration file (default: config.json next to the jar)
                  --no-client      host without starting the Minecraft client (dedicated mode)
                  --no-gui         text mode (default when started without a command: graphical interface)
                  --gui            force the graphical interface
                  --yes            do not ask for confirmation
                  --debug          verbose logging

                While hosting, type in this window:
                  stop             stop the server, upload the world and release the lock
                  players          list online players
                  /<command>       send a command to the Minecraft server console
                """.formatted(VERSION));
    }

    /** config.json in the working directory, otherwise next to the jar. */
    static Path defaultConfigPath() {
        Path cwd = Path.of("config.json");
        if (Files.exists(cwd)) return cwd.toAbsolutePath();
        try {
            Path jar = Path.of(MainOrchestrator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir != null) return dir.resolve("config.json");
        } catch (Exception ignored) {
            // fall back to cwd
        }
        return cwd.toAbsolutePath();
    }

    /**
     * Loads and prepares the configuration exactly like the CLI {@code run} command: dependency
     * checks / installers, server pack detection, Tailscale IP resolution.
     */
    public static Prepared bootstrap(Path cfgFile, boolean launchClient) throws IOException {
        if (log == null) log = LoggerFactory.getLogger(MainOrchestrator.class);
        DependencyInstaller installer = new DependencyInstaller(
                cfgFile.toAbsolutePath().getParent() != null ? cfgFile.toAbsolutePath().getParent() : Path.of("."));
        Config cfg = Config.load(cfgFile);
        String gitExe = cfg.isGitBackend() ? installer.ensureGitInstalled() : "git";
        if (cfg.activeWorld().isPresent()) {
            com.mcsync.world.WorldService svc = new com.mcsync.world.WorldService(
                    cfgFile, cfg, gitExe, com.mcsync.world.SourceResolver.combined(cfg.curseforgeApiKey()),
                    installer::findExistingPrismLauncher, null);
            cfg = svc.configFor(cfg.activeWorld().get());
        }
        cfg = installer.ensureAll(cfg, launchClient);
        cfg = resolveTailscaleIp(cfg);
        logClientAddressResolution(cfg);
        return new Prepared(cfg, gitExe);
    }

    private static void logClientAddressResolution(Config cfg) {
        String configured = cfg.localClientAddress();
        String effective = cfg.effectiveClientAddress();
        if (!configured.isBlank() && !configured.equalsIgnoreCase("auto") && !configured.equals(effective)) {
            log.info("localClientAddress {} would not reach a server bound to {} (getsockopt timeout): "
                    + "the local client will connect to {}", configured, cfg.localTailscaleIp(), effective);
        }
    }

    public static int initConfig(Path target) {
        if (Files.exists(target)) {
            System.out.println(target.toAbsolutePath() + " already exists.");
            return EXIT_OK;
        }
        try (InputStream in = MainOrchestrator.class.getResourceAsStream("/config.example.json")) {
            if (in == null) throw new IOException("template not bundled");
            Files.copy(in, target);
            System.out.println("Created " + target.toAbsolutePath() + " - edit it before running the launcher.");
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("Cannot create " + target + ": " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    public static Config resolveTailscaleIp(Config cfg) {
        String ip = cfg.localTailscaleIp();
        if ("auto".equalsIgnoreCase(ip)) {
            String detected = NetUtils.detectTailscaleIp();
            if (detected == null) {
                throw new IllegalStateException("Cannot detect the Tailscale IP. Is Tailscale installed and connected? "
                        + "You can also set \"localTailscaleIp\" explicitly in config.json.");
            }
            log.info("Detected Tailscale IP: {}", detected);
            return cfg.withLocalTailscaleIp(detected);
        }
        try {
            if (NetworkInterface.getByInetAddress(InetAddress.getByName(ip)) == null) {
                log.warn("localTailscaleIp {} is not assigned to any network interface of this PC "
                        + "(Tailscale disconnected?). The server will fail to bind.", ip);
            }
        } catch (IOException e) {
            log.warn("Invalid localTailscaleIp '{}': {}", ip, e.getMessage());
        }
        return cfg;
    }

    private static volatile boolean fileLogEnabled;

    /** Mirrors console output into {@code .mcsync/logs/launcher-<date>.log}. */
    public static synchronized void enableFileLog(Config cfg) {
        if (fileLogEnabled) return;
        try {
            Path logs = cfg.stateDir().resolve("logs");
            Files.createDirectories(logs);
            String name = "launcher-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                    .format(Instant.now()) + ".log";
            OutputStream file = new BufferedOutputStream(Files.newOutputStream(logs.resolve(name),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            String enc = System.getProperty("stdout.encoding");
            Charset cs = enc != null ? Charset.forName(enc) : Charset.defaultCharset();
            System.setOut(new PrintStream(new TeeOutputStream(System.out, file), true, cs));
            fileLogEnabled = true;
        } catch (Exception e) {
            log.debug("File logging disabled: {}", e.getMessage());
        }
    }

    // ==========================================================================================
    // run: the orchestration lifecycle
    // ==========================================================================================

    public int run() throws Exception {
        printBanner();
        FileLock instanceLock = acquireInstanceLock();
        if (instanceLock == null) {
            log.error("Another mcServer4all instance is already running on this PC for {}", cfg.serverDir());
            return EXIT_ERROR;
        }
        Thread hook = new Thread(this::onJvmShutdown, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);

        try {
            if (cfg.isGitBackend()) {
                log.info("Checking access to private Git repo '{}' (branch {}) ...", cfg.gitRemoteUrl(), cfg.gitBranch());
            } else {
                log.info("Checking access to bucket '{}' ...", cfg.s3Bucket());
            }
            storage.checkAccess();

            Instant waitStarted = Instant.now();
            while (true) {
                if (shutdownSignal.isDone()) return EXIT_OK;
                transition(OrchestratorState.CHECKING_LOCK);
                LockManager.Inspection insp = lockManager.inspect();
                switch (insp.state()) {
                    case FREE -> {
                        transition(OrchestratorState.ACQUIRING_LOCK);
                        if (lockManager.tryAcquire()) return host(false);
                        log.info("Someone else acquired the lock first, re-checking ...");
                    }
                    case OWN_LEFTOVER -> {
                        log.warn("Found a lock left by this player (session {}, heartbeat {}s old): "
                                + "the previous session ended abnormally. Recovering.", insp.lock().sessionId(), insp.heartbeatAgeSeconds());
                        transition(OrchestratorState.ACQUIRING_LOCK);
                        if (lockManager.breakAndAcquire(insp)) return host(true);
                    }
                    case STALE -> {
                        log.warn("!!! STALE LOCK: host '{}' ({}) stopped heartbeats {}s ago and does not answer on port {}.",
                                insp.lock().ownerId(), insp.lock().hostIp(), insp.heartbeatAgeSeconds(), insp.lock().port());
                        transition(OrchestratorState.ACQUIRING_LOCK);
                        if (lockManager.breakAndAcquire(insp)) {
                            discord.staleLockBroken(insp.lock().ownerId(), cfg.playerId());
                            return host(false);
                        }
                    }
                    case HELD -> {
                        LockFile lock = insp.lock();
                        if (lock.status() == LockFile.Status.ONLINE
                                && NetUtils.tcpProbe(lock.hostIp(), lock.port(), 3000)) {
                            return joinAsClient(lock);
                        }
                        transition(OrchestratorState.WAITING_FOR_HOST);
                        long waited = Duration.between(waitStarted, Instant.now()).getSeconds();
                        if (waited > cfg.hostWaitTimeoutSeconds()) {
                            log.error("Gave up waiting for host '{}' after {}s (status {}). Check that both PCs are "
                                    + "connected to Tailscale and that the Windows firewall allows port {}.",
                                    lock.ownerId(), waited, lock.status(), lock.port());
                            return EXIT_ERROR;
                        }
                        if (lock.status() == LockFile.Status.ONLINE && waited >= 30 && waited % 30 < 5) {
                            log.warn("Hint: if this lasts, run 'tailscale ping {}'. \"no matching peer\" means the two PCs "
                                    + "are not in the same Tailnet: share the node / invite the friend in the Tailscale "
                                    + "admin console.", lock.hostIp());
                        }
                        switch (lock.status()) {
                            case STARTING -> log.info("'{}' is starting the server, waiting ... ({}s)", lock.ownerId(), waited);
                            case STOPPING -> log.info("'{}' is stopping and uploading the world, waiting to take over ... ({}s)",
                                    lock.ownerId(), waited);
                            case ONLINE -> log.info("'{}' reports ONLINE but {}:{} is not reachable yet, retrying ... ({}s)",
                                    lock.ownerId(), lock.hostIp(), lock.port(), waited);
                        }
                        sleepOrShutdown(5000);
                    }
                }
            }
        } catch (Exception e) {
            transition(OrchestratorState.FAILED);
            log.error("Error: {}", e.getMessage());
            log.debug("Stack trace", e);
            teardown("error: " + e.getMessage());
            return EXIT_ERROR;
        } finally {
            if (!teardownDone.get() && lockManager.isHeld()) teardown("exit");
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
            try {
                instanceLock.release();
                instanceLock.channel().close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Client mode
    // ------------------------------------------------------------------------------------------

    private int joinAsClient(LockFile lock) throws Exception {
        transition(OrchestratorState.CLIENT_MODE);
        log.info("'{}' is hosting at {} - joining as client", lock.ownerId(), lock.address());
        currentHost = lock;
        safeObserve(o -> o.joinedHost(lock));
        try {
            modsSync.syncAsClient();
        } catch (ModsSyncManager.ModMismatchException e) {
            log.error(e.getMessage());
            return EXIT_ERROR;
        }
        discord.clientJoined(cfg.playerId(), lock.hostIp());
        if (!clientLauncher.isConfigured() || !launchClient) {
            log.info("Connect your Minecraft client to: {}", lock.address());
            transition(OrchestratorState.TERMINATED);
            return EXIT_OK;
        }
        Process client = clientLauncher.launch(lock.hostIp(), lock.port());
        log.info("Have fun! This window will close when the client exits.");
        int code = client.waitFor();
        log.info("Client exited (code {})", code);
        transition(OrchestratorState.TERMINATED);
        discord.flush(Duration.ofSeconds(5));
        return EXIT_OK;
    }

    // ------------------------------------------------------------------------------------------
    // Host mode
    // ------------------------------------------------------------------------------------------

    private int host(boolean ownRecovery) throws Exception {
        lockManager.startHeartbeat(this::onLockLost);

        // ---- World ---------------------------------------------------------------------------
        transition(OrchestratorState.SYNCING_DOWN);
        ServerProcessController probeController = new ServerProcessController(cfg, new ServerProcessController.Listener() {
        });
        probeController.killOrphanedServer();
        probeController.shutdownMonitor();

        Optional<WorldManifest> remote = worldSync.fetchRemoteManifest();
        LocalState local = localState.get();
        boolean worldExists = Files.isDirectory(cfg.worldDir());
        if (remote.isEmpty()) {
            log.info(worldExists ? "No remote world yet: the local world will be uploaded at the end of the session"
                    : "No remote world and no local world: the server will generate a new one");
        } else {
            WorldManifest rm = remote.get();
            log.info("Remote world v{} by '{}' ({}), local world v{}{}", rm.version(), rm.uploadedBy(),
                    Instant.ofEpochSecond(rm.lastUpdated()), local.worldVersion(), local.dirty() ? " (unsynced changes)" : "");
            if (local.dirty() && worldExists && rm.version() == local.worldVersion()) {
                log.warn("The previous session on this PC was not uploaded (crash/power loss). The local world is newer "
                        + "than the remote one and will be used{}.", ownRecovery ? " (own lock recovered)" : "");
            } else {
                boolean upToDate = worldExists && !local.dirty() && rm.version() == local.worldVersion()
                        && rm.sha256() != null && rm.sha256().equalsIgnoreCase(local.worldSha256());
                if (upToDate) {
                    log.info("Local world is up to date");
                } else {
                    if (local.dirty()) {
                        log.warn("Local unsynced changes are superseded by remote v{}; they are kept in "
                                + ".local_backup/world_pre_sync.tar.zst", rm.version());
                    }
                    if (!rm.cleanShutdown()) {
                        log.warn("Remote snapshot v{} was saved after an unclean shutdown of '{}'", rm.version(), rm.uploadedBy());
                    }
                    worldSync.pullWorld(rm);
                }
            }
        }
        if (shutdownSignal.isDone()) return finishHost("shutdown requested");

        // ---- Mods ----------------------------------------------------------------------------
        transition(OrchestratorState.SYNCING_MODS);
        boolean singleJvmMode = launchClient && cfg.clientInstanceDir() != null && clientLauncher.isConfigured();
        try {
            if (singleJvmMode && modsSync.isLocked()) {
                // Single-JVM Integrated Host: only the Client instance is needed! No double JVM or duplicate server install.
                modsSync.syncAsClient();
            } else {
                modsSync.syncAsHost();
                modsSync.lockedStartCommand().ifPresent(cmd -> cfg = cfg.withServerStartCommand(cmd));
            }
        } catch (ModsSyncManager.ModMismatchException e) {
            log.error(e.getMessage());
            teardown("mod mismatch");
            return EXIT_ERROR;
        }
        if (shutdownSignal.isDone()) return finishHost("shutdown requested");

        // ---- Single-JVM Integrated Host (Pilastro 2) vs Headless Dedicated Server ------------
        if (singleJvmMode) {
            transition(OrchestratorState.STARTING_SERVER);
            integratedHost = new com.mcsync.core.IntegratedHostController(cfg, new ServerListener());
            integratedHost.prepareClientWorldForHosting();
            localState.update(s -> s.withDirty(true, lockManager.sessionId()));
            integratedHost.startSingleJvmHost(clientLauncher);

            long version = Math.max(localState.get().worldVersion(), remote.map(WorldManifest::version).orElse(0L));
            lockManager.updateStatus(LockFile.Status.ONLINE);
            discord.serverOnline(cfg.playerId(), cfg.localTailscaleIp(), cfg.serverPort(), version);
            transition(OrchestratorState.HOSTING);
            if (consoleInputEnabled) startConsoleInput();
            log.info("==> Single-JVM Integrated Host ONLINE at {}:{} (50% RAM saved! Exit world to Main Menu for Fast Handover)",
                    cfg.localTailscaleIp(), cfg.serverPort());

            String reason = shutdownSignal.get();
            return finishHost(reason);
        }

        // ---- Headless Dedicated Server (--no-client) -----------------------------------------
        transition(OrchestratorState.STARTING_SERVER);
        server = new ServerProcessController(cfg, new ServerListener());
        server.prepareServerProperties();
        localState.update(s -> s.withDirty(true, lockManager.sessionId()));
        server.start();
        if (!server.awaitReady(Duration.ofSeconds(cfg.serverStartTimeoutSeconds()))) {
            log.error("The server did not become ready (see the [server] output above / logs/latest.log)");
            server.stopGracefully();
            // The world on disk may be half-migrated: force a fresh download next time.
            if (remote.isPresent()) {
                localState.update(s -> s.withWorld(s.worldVersion(), null, s.lastSyncEpoch()));
            } else {
                localState.update(s -> s.withDirty(false, null));
            }
            discord.alert("Server failed to start", "Host " + cfg.playerId() + " could not boot the server. Lock released.");
            server = null;
            teardown("server failed to start");
            return EXIT_ERROR;
        }

        long version = Math.max(localState.get().worldVersion(), remote.map(WorldManifest::version).orElse(0L));
        lockManager.updateStatus(LockFile.Status.ONLINE);
        discord.serverOnline(cfg.playerId(), cfg.localTailscaleIp(), cfg.serverPort(), version);
        transition(OrchestratorState.HOSTING);
        server.startMonitoring();
        if (consoleInputEnabled) startConsoleInput();
        log.info("==> Server ONLINE at {}:{}  (type 'stop' here to end the session)", cfg.localTailscaleIp(), cfg.serverPort());

        if (launchClient && clientLauncher.isConfigured()) {
            String address = cfg.effectiveClientAddress();
            try {
                server.watchClient(clientLauncher.launch(address, cfg.serverPort()));
            } catch (IOException e) {
                log.error(e.getMessage());
                server.setMode(ServerProcessController.HostMode.DAEMON_HOSTING);
            }
        } else {
            log.info("Dedicated mode: the server stops when no players are online");
            server.setMode(ServerProcessController.HostMode.DAEMON_HOSTING);
        }

        String reason = shutdownSignal.get();
        return finishHost(reason);
    }

    private int finishHost(String reason) {
        boolean ok = teardown(reason);
        return ok && !serverCrashed ? EXIT_OK : EXIT_ERROR;
    }

    /** Server events: player-aware shutdown and watchdog alerts. */
    private final class ServerListener implements ServerProcessController.Listener {
        @Override
        public void onConsoleLine(String line) {
            safeObserve(o -> o.serverLine(line));
        }

        @Override
        public void onPlayerJoined(String name, int online) {
            ServerProcessController s = server;
            if (s != null) safeObserve(o -> o.playersChanged(s.onlinePlayers()));
        }

        @Override
        public void onPlayerLeft(String name, int online) {
            ServerProcessController s = server;
            if (s != null) safeObserve(o -> o.playersChanged(s.onlinePlayers()));
        }

        @Override
        public void onIdleShutdown() {
            requestShutdown("no players online");
        }

        @Override
        public void onHostModeChanged(ServerProcessController.HostMode mode) {
            if (mode == ServerProcessController.HostMode.DAEMON_HOSTING) {
                transition(OrchestratorState.DAEMON_HOSTING);
                log.info("Daemon hosting: {} player(s) online, the server stops {}s after the last one leaves",
                        server != null ? server.onlinePlayers().size() : 0, cfg.idleShutdownSeconds());
            }
        }

        @Override
        public void onOutOfMemory() {
            discord.alert("Server Out Of Memory",
                    "The server hosted by " + cfg.playerId() + " ran out of memory and is being restarted/stopped. "
                            + "Increase -Xmx in the server start command.");
        }

        @Override
        public void onTickWatchdog(String seconds) {
            discord.alert("Critical Server Lag",
                    "A single server tick took " + seconds + " seconds on " + cfg.playerId() + "'s server.");
        }

        @Override
        public void onServerExited(int exitCode, boolean expected) {
            if (!expected) {
                serverCrashed = true;
                log.error("The server stopped unexpectedly (exit code {})", exitCode);
                discord.alert("Server Crashed", "The server hosted by " + cfg.playerId()
                        + " exited unexpectedly (code " + exitCode + "). Saving the world anyway.");
                requestShutdown("server exited (code " + exitCode + ")");
            } else if (state == OrchestratorState.HOSTING || state == OrchestratorState.DAEMON_HOSTING) {
                // "stop" typed in the server console directly.
                requestShutdown("server stopped");
            }
        }
    }

    private void onLockLost(LockFile newOwner) {
        lockLost = true;
        String who = newOwner != null ? newOwner.ownerId() : "nobody (lock deleted)";
        log.error("!!! The sync lock was lost (now owned by {}). This session will be saved as a conflict copy, "
                + "NOT as the main world.", who);
        discord.alert("Lock Lost", "Host " + cfg.playerId() + " lost the lock to " + who
                + ". Its session will be uploaded to conflicts/ for manual recovery.");
        ServerProcessController s = server;
        if (s != null) s.sendCommand("say WARNING: world sync lock lost - this session will be saved as a conflict copy");
    }

    // ------------------------------------------------------------------------------------------
    // Teardown (shared by normal exit, errors and the shutdown hook)
    // ------------------------------------------------------------------------------------------

    private void requestShutdown(String reason) {
        if (shutdownSignal.complete(reason)) log.info("Shutdown requested: {}", reason);
    }

    /**
     * Stops the server, uploads the world and releases the lock. Idempotent and thread-safe.
     *
     * @return false if the world could not be uploaded
     */
    private boolean teardown(String reason) {
        synchronized (teardownMonitor) {
            if (!teardownDone.compareAndSet(false, true)) return true;
            boolean success = true;
            ServerProcessController s = server;
            com.mcsync.core.IntegratedHostController ih = integratedHost;
            try {
                transition(OrchestratorState.SHUTTING_DOWN);
                if (lockManager.isHeld()) lockManager.updateStatus(LockFile.Status.STOPPING);
                Instant startedAt = s != null ? s.readyAt() : (ih != null ? ih.readyAt() : null);
                boolean clean = true;
                if (s != null) {
                    clean = s.stopGracefully() && !serverCrashed;
                    s.shutdownMonitor();
                }
                if (ih != null) {
                    ih.stopGracefully();
                    ih.syncSaveBackToWorldDir();
                }

                boolean wasStarted = (s != null && s.wasStarted()) || ih != null;
                if (wasStarted && (lockManager.isHeld() || lockLost)) {
                    transition(OrchestratorState.SYNCING_UP);
                    if (lockLost) {
                        try {
                            String key = worldSync.pushConflictCopy();
                            log.warn("Session saved to {} (lock was lost). Restore it manually if needed.", key);
                        } catch (Exception e) {
                            log.error("Could not upload the conflict copy: {}", e.getMessage());
                        }
                    } else {
                        success = pushWithRetry(clean, startedAt);
                    }
                }

                if (!success) {
                    lockManager.stopHeartbeat();
                    log.error("The world was NOT uploaded. The lock is kept so nobody plays an old world; "
                            + "run the launcher again on this PC to retry the upload.");
                    discord.alert("World Upload Failed", "Host " + cfg.playerId()
                            + " could not upload the world. The lock is kept; it will retry on the next launch.");
                } else {
                    transition(OrchestratorState.RELEASING_LOCK);
                    lockManager.release();
                }
                transition(success ? OrchestratorState.TERMINATED : OrchestratorState.FAILED);
                log.info("Session finished ({})", reason);
            } catch (Exception e) {
                success = false;
                log.error("Teardown error: {}", e.getMessage());
                log.debug("Stack trace", e);
            } finally {
                discord.flush(Duration.ofSeconds(10));
            }
            return success;
        }
    }

    private boolean pushWithRetry(boolean clean, Instant startedAt) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                StorageSyncManager.PushResult r = worldSync.pushWorld(lockManager.sessionId(), clean);
                Duration d = startedAt != null ? Duration.between(startedAt, Instant.now()) : Duration.ZERO;
                discord.worldSynced(d, r.archiveBytes());
                return true;
            } catch (Exception e) {
                log.error("World upload failed (attempt {}/3): {}", attempt, e.getMessage());
                log.debug("Stack trace", e);
                if (attempt < 3) FileUtils.sleepQuietly(5000L * attempt);
            }
        }
        return false;
    }

    /** JVM shutdown hook (Ctrl+C, console closed, logoff). */
    private void onJvmShutdown() {
        if (teardownDone.get() && !lockManager.isHeld()) return;
        OrchestratorState st = state;
        if (st == OrchestratorState.CLIENT_MODE || st == OrchestratorState.TERMINATED
                || st == OrchestratorState.WAITING_FOR_HOST || st == OrchestratorState.CHECKING_LOCK) {
            return;
        }
        System.out.println();
        log.warn("Launcher is being closed: stopping the server and saving the world. Please wait ...");
        requestShutdown("launcher closed");
        teardown("launcher closed");
    }

    // ------------------------------------------------------------------------------------------
    // Console commands while hosting
    // ------------------------------------------------------------------------------------------

    private void startConsoleInput() {
        Thread t = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, consoleCharset()));
                String line;
                while ((line = in.readLine()) != null) {
                    String cmd = line.trim();
                    if (cmd.isEmpty()) continue;
                    ServerProcessController s = server;
                    switch (cmd.toLowerCase(Locale.ROOT)) {
                        case "stop", "exit", "quit", "!shutdown" -> {
                            requestShutdown("stop typed in the launcher console");
                            return;
                        }
                        case "players", "list" -> log.info("Online players: {}", s != null ? s.onlinePlayers() : Set.of());
                        case "status" -> log.info("State: {}, mode: {}, players: {}", state,
                                s != null ? s.mode() : "-", s != null ? s.onlinePlayers() : Set.of());
                        case "help", "?" -> log.info("Commands: stop | players | status | /<server command>");
                        default -> {
                            String forward = cmd.startsWith("/") ? cmd.substring(1) : cmd;
                            if (s == null || !s.sendCommand(forward)) log.warn("Server not running");
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Console input closed: {}", e.getMessage());
            }
        }, "console-input");
        t.setDaemon(true);
        t.start();
    }

    private static Charset consoleCharset() {
        String enc = System.getProperty("stdin.encoding", System.getProperty("stdout.encoding"));
        try {
            return enc != null ? Charset.forName(enc) : Charset.defaultCharset();
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ==========================================================================================
    // Utility commands
    // ==========================================================================================

    // ==========================================================================================
    // API for the GUI
    // ==========================================================================================

    public void setObserver(Observer o) {
        this.observer = o != null ? o : new Observer() {
        };
    }

    /** Disables the stdin command reader (the GUI has its own command bar). */
    public void setConsoleInputEnabled(boolean enabled) {
        this.consoleInputEnabled = enabled;
    }

    /** Graceful stop: stop server, upload world, release lock (async). */
    public void requestStop(String reason) {
        requestShutdown(reason);
    }

    public boolean sendServerCommand(String command) {
        ServerProcessController s = server;
        String c = command.startsWith("/") ? command.substring(1) : command;
        return s != null && s.sendCommand(c);
    }

    public OrchestratorState state() {
        return state;
    }

    public Config config() {
        return cfg;
    }

    /** The local server controller while hosting, otherwise null. */
    public ServerProcessController serverController() {
        return server;
    }

    /** Remote host this PC joined as a client (null when hosting / idle). */
    public LockFile currentHost() {
        return currentHost;
    }

    public Snapshot snapshot() throws IOException {
        storage.checkAccess();
        LockManager.Inspection insp = lockManager.inspect();
        Optional<WorldManifest> m = worldSync.fetchRemoteManifest();
        int backups;
        try {
            backups = worldSync.listBackups().size();
        } catch (RuntimeException e) {
            backups = 0;
        }
        return new Snapshot(insp, m, localState.get(), backups, modsSync.hasPendingUpdates());
    }

    private void safeObserve(java.util.function.Consumer<Observer> c) {
        try {
            c.accept(observer);
        } catch (RuntimeException e) {
            log.debug("Observer error: {}", e.getMessage());
        }
    }

    public int printStatus() throws IOException {
        storage.checkAccess();
        LockManager.Inspection insp = lockManager.inspect();
        System.out.println("Lock:        " + switch (insp.state()) {
            case FREE -> "FREE - nobody is hosting";
            case HELD -> "HELD by " + insp.lock().ownerId() + " @ " + insp.lock().address() + " [" + insp.lock().status()
                    + ", heartbeat " + insp.heartbeatAgeSeconds() + "s ago]";
            case STALE -> "STALE (owner " + insp.lock().ownerId() + ", heartbeat " + insp.heartbeatAgeSeconds() + "s ago)";
            case OWN_LEFTOVER -> "LEFT OVER by this player (previous crash) - will be recovered on next run";
        });
        Optional<WorldManifest> m = worldSync.fetchRemoteManifest();
        System.out.println("Remote world: " + m.map(w -> "v" + w.version() + " by " + w.uploadedBy() + " at "
                + Instant.ofEpochSecond(w.lastUpdated()) + " (" + FileUtils.humanSize(w.sizeBytes()) + ")"
                + (w.cleanShutdown() ? "" : " [unclean]")).orElse("none"));
        LocalState ls = localState.get();
        System.out.println("Local world:  v" + ls.worldVersion() + (ls.dirty() ? " (UNSYNCED CHANGES)" : "")
                + " in " + cfg.worldDir());
        System.out.println("Backups:      " + worldSync.listBackups().size() + " / " + cfg.maxBackupsToKeep());
        System.out.println("Tailscale IP: " + cfg.localTailscaleIp());
        return EXIT_OK;
    }

    public int listBackups() {
        storage.checkAccess();
        List<StorageSyncManager.Backup> backups = worldSync.listBackups();
        if (backups.isEmpty()) System.out.println("No backups.");
        for (StorageSyncManager.Backup b : backups) {
            System.out.printf("%-50s  v%-5d %s  %s%n", b.key(), b.version(),
                    Instant.ofEpochSecond(b.timestamp()), FileUtils.humanSize(b.size()));
        }
        return EXIT_OK;
    }

    public int forceUnlock(boolean assumeYes) throws IOException {
        storage.checkAccess();
        LockManager.Inspection insp = lockManager.inspect();
        if (insp.state() == LockManager.State.FREE) {
            System.out.println("No lock present.");
            return EXIT_OK;
        }
        System.out.println("Lock owned by " + insp.lock().ownerId() + " (" + insp.lock().hostIp()
                + "), heartbeat " + insp.heartbeatAgeSeconds() + "s ago.");
        if (!assumeYes && !confirm("Delete it? Only do this if nobody is hosting.")) return EXIT_ERROR;
        if (insp.state() == LockManager.State.HELD && insp.lock().status() == LockFile.Status.ONLINE
                && NetUtils.tcpProbe(insp.lock().hostIp(), insp.lock().port(), 3000)) {
            log.warn("The server of '{}' still answers on {}: deleting the lock anyway as requested",
                    insp.lock().ownerId(), insp.lock().address());
        }
        storage.delete(LockManager.LOCK_KEY);
        System.out.println("Lock deleted.");
        return EXIT_OK;
    }

    public int restore(String key, boolean assumeYes) throws IOException {
        if (key == null) {
            System.err.println("Usage: restore <backup key>   (see list-backups)");
            return EXIT_CONFIG;
        }
        storage.checkAccess();
        if (!assumeYes && !confirm("Make " + key + " the current world for everybody?")) return EXIT_ERROR;
        if (!lockManager.tryAcquire()) {
            log.error("Somebody is hosting right now: restore aborted");
            return EXIT_ERROR;
        }
        try {
            WorldManifest m = worldSync.restoreBackup(key, lockManager.sessionId());
            log.info("Restored {} as world v{}", key, m.version());
            discord.info("World Restored", cfg.playerId() + " restored backup " + key + " as v" + m.version() + ".");
        } finally {
            lockManager.release();
            discord.flush(Duration.ofSeconds(5));
        }
        return EXIT_OK;
    }

    public int pushMods() throws Exception {
        storage.checkAccess();
        if (!lockManager.tryAcquire()) {
            log.error("Somebody is hosting right now: try again later");
            return EXIT_ERROR;
        }
        try {
            modsSync.forcePublish();
        } finally {
            lockManager.release();
        }
        return EXIT_OK;
    }

    public int pullMods() throws Exception {
        storage.checkAccess();
        modsSync.forcePull();
        return EXIT_OK;
    }

    private static boolean confirm(String question) {
        System.out.print(question + " Type YES to confirm: ");
        System.out.flush();
        try {
            String answer = new BufferedReader(new InputStreamReader(System.in, consoleCharset())).readLine();
            return answer != null && answer.trim().equals("YES");
        } catch (IOException e) {
            return false;
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private void transition(OrchestratorState next) {
        OrchestratorState prev = state;
        if (prev == next) return;
        state = next;
        log.debug("State: {} -> {}", prev, next);
        safeObserve(o -> o.stateChanged(next));
    }

    private void sleepOrShutdown(long millis) {
        try {
            shutdownSignal.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // timeout = keep going
        }
    }

    private FileLock acquireInstanceLock() throws IOException {
        Files.createDirectories(cfg.stateDir());
        FileChannel ch = FileChannel.open(cfg.stateDir().resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) ch.close();
        return lock;
    }

    private void printBanner() {
        log.info("==============================================================");
        log.info(" mcServer4all {}  |  player: {}  |  Java {}", VERSION, cfg.playerId(), Runtime.version().feature());
        log.info(" Server dir: {}", cfg.serverDir());
        log.info(" Tailscale:  {}:{}", cfg.localTailscaleIp(), cfg.serverPort());
        log.info("==============================================================");
        if (!Files.isDirectory(cfg.serverDir())) {
            throw new IllegalStateException("serverDirectory does not exist: " + cfg.serverDir());
        }
    }

    public void close() {
        lockManager.close();
        storage.close();
    }
}
