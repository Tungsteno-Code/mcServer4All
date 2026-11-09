package com.mcsync.core;

import com.mcsync.config.Config;
import com.mcsync.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Single-JVM "Client-as-Server" (Integrated Host) controller (Pilastro 2 of v2.1 / v3.0):
 * <ul>
 *     <li>Replaces running a separate 7 GB Dedicated Server JVM on the Host PC.</li>
 *     <li>Copies/links the synchronized world into {@code <prismInstance>/minecraft/saves/<worldFolderName>}
 *     and strips {@code Data.Player} from {@code level.dat} via {@link LevelDatCleaner}.</li>
 *     <li>Sets {@code pauseOnLostFocus:false} in {@code options.txt} so Alt-Tab never pauses the server for friends.</li>
 *     <li>Injects a lightweight KubeJS auto-LAN script (when KubeJS is present) or monitors {@code logs/latest.log}
 *     for {@code Local game hosted on port <N>} and automatically bridges {@code 25565 -> <N>} over TCP if
 *     Minecraft opened LAN on a random port!</li>
 *     <li>Supports <b>Fast Handover</b>: detects when the Host exits the world back to the Main Menu
 *     ({@code Stopping server} / {@code Saving worlds}) and immediately triggers world delta upload + lock release
 *     in ~10 seconds without requiring the player to close Minecraft!</li>
 * </ul>
 */
public final class IntegratedHostController {

    private static final Logger log = LoggerFactory.getLogger(IntegratedHostController.class);

    private static final Pattern LAN_PORT_PATTERN = Pattern.compile(
            ".*(?:Local game hosted on port|Started serving on| Ospitato su porta|remoto avviato sulla porta)\\s+(\\d+).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INTEGRATED_READY = Pattern.compile(
            ".*(?:Time elapsed: \\d+ ms|Changing view distance to|Preparing start region|IntegratedServer).*");
    private static final Pattern INTEGRATED_STOPPED = Pattern.compile(
            ".*(?:Stopping server|All worlds saved|ThreadedAnvilChunkStorage: All dimensions are saved).*");

    private final Config cfg;
    private final ServerProcessController.Listener listener;
    private final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private volatile Process clientProcess;
    private volatile ProcessHandle delegatedMcJvm;
    private volatile ServerSocket tcpBridgeServer;
    private volatile int internalLanPort = -1;
    private volatile boolean stopping;
    private volatile Instant readyAt;
    private volatile Instant sessionStartInstant = Instant.now();
    private volatile Runnable onReadyCallback;

    public IntegratedHostController(Config cfg, ServerProcessController.Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    public void setOnReadyCallback(Runnable onReadyCallback) {
        this.onReadyCallback = onReadyCallback;
    }

    /** Returns the saves/<worldName> path inside the Prism client instance. */
    public Path clientWorldSaveDir() {
        Path gameDir = cfg.clientInstanceDir();
        if (gameDir == null) return cfg.worldDir();
        return gameDir.resolve("saves").resolve(cfg.worldFolderName());
    }

    /**
     * Copies the synced world from {@code cfg.worldDir()} into {@code <clientInstance>/saves/<worldFolderName>},
     * cleans {@code Data.Player} in {@code level.dat}, ensures {@code pauseOnLostFocus:false} in {@code options.txt},
     * and injects KubeJS auto-LAN helper if {@code kubejs/} is present.
     */
    public void prepareClientWorldForHosting() throws IOException {
        this.sessionStartInstant = Instant.now();
        Path gameDir = cfg.clientInstanceDir();
        if (gameDir == null) {
            throw new IOException("clientInstanceDirectory non configurata per Single-JVM Host");
        }
        Files.createDirectories(gameDir.resolve("saves"));
        Path saveDir = clientWorldSaveDir();
        Path syncedWorld = cfg.worldDir();

        boolean syncedHasLevelDat = Files.isRegularFile(syncedWorld.resolve("level.dat"));
        boolean saveHasLevelDat = Files.isRegularFile(saveDir.resolve("level.dat"));

        if (syncedHasLevelDat) {
            if (!syncedWorld.equals(saveDir)) {
                syncDirectory(syncedWorld, saveDir);
            }
            LevelDatCleaner.cleanWorldDir(saveDir);
        } else if (saveHasLevelDat) {
            LevelDatCleaner.cleanWorldDir(saveDir);
            if (!syncedWorld.equals(saveDir)) {
                syncDirectory(saveDir, syncedWorld);
            }
        } else {
            // Do NOT create an empty saves/world folder without level.dat, as it confuses Minecraft Singleplayer
            if (Files.exists(saveDir)) {
                FileUtils.deleteRecursively(saveDir);
            }
        }
        ensurePauseOnLostFocusDisabled(gameDir);
        configurePrismQuickPlaySingleplayer(gameDir.getParent(), cfg.worldFolderName());
        injectKubeJsAutoLanIfPresent(gameDir, cfg.serverPort());
    }

    /** Clears `--quickPlaySingleplayer` from `instance.cfg` when joining as a Client or ending Host session. */
    public static void clearPrismQuickPlay(Path gameDir) {
        if (gameDir == null || gameDir.getParent() == null) return;
        Path instanceCfg = gameDir.getParent().resolve("instance.cfg");
        if (!Files.isRegularFile(instanceCfg)) return;
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(instanceCfg, StandardCharsets.UTF_8));
            boolean changed = lines.removeIf(l -> l.startsWith("JvmArgs=") && l.contains("--quickPlaySingleplayer"));
            if (changed) {
                Files.write(instanceCfg, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private static void configurePrismQuickPlaySingleplayer(Path instanceDir, String worldFolderName) {
        if (instanceDir == null) return;
        Path instanceCfg = instanceDir.resolve("instance.cfg");
        if (!Files.isRegularFile(instanceCfg)) return;
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(instanceCfg, StandardCharsets.UTF_8));
            boolean changed = false;
            boolean hasOverride = false;
            boolean hasMax = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("OverrideMemory=")) {
                    hasOverride = true;
                    if (!line.equals("OverrideMemory=true")) {
                        lines.set(i, "OverrideMemory=true");
                        changed = true;
                    }
                } else if (line.startsWith("MaxMemAlloc=")) {
                    hasMax = true;
                    try {
                        int cur = Integer.parseInt(line.substring("MaxMemAlloc=".length()).trim());
                        if (cur < 8192) {
                            lines.set(i, "MaxMemAlloc=8192");
                            changed = true;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!hasOverride) {
                lines.add("OverrideMemory=true");
                changed = true;
            }
            if (!hasMax) {
                lines.add("MinMemAlloc=4096");
                lines.add("MaxMemAlloc=8192");
                changed = true;
            }
            if (changed) {
                Files.write(instanceCfg, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Copies the modified world back from {@code <clientInstance>/saves/<worldFolderName>} into
     * {@code cfg.worldDir()} and strips {@code Data.Player} so {@link StorageSyncManager#pushWorld}
     * uploads the exact delta.
     */
    public void syncSaveBackToWorldDir() throws IOException {
        Path gameDir = cfg.clientInstanceDir();
        Path saveDir = clientWorldSaveDir();
        Path syncedWorld = cfg.worldDir();

        // Check if the player created a new world under a different folder name in saves/ during this session
        if (gameDir != null && Files.isDirectory(gameDir.resolve("saves"))) {
            Path bestCandidate = findMostRecentWorldSave(gameDir.resolve("saves"), saveDir);
            if (bestCandidate != null && !bestCandidate.equals(saveDir)) {
                log.info("Rilevato mondo salvato in '{}' -> allineamento con '{}'",
                        bestCandidate.getFileName(), saveDir.getFileName());
                syncDirectory(bestCandidate, saveDir);
            }
        }

        if (Files.isRegularFile(saveDir.resolve("level.dat"))) {
            if (!saveDir.equals(syncedWorld)) {
                syncDirectory(saveDir, syncedWorld);
            }
            LevelDatCleaner.cleanWorldDir(syncedWorld);
        } else if (Files.exists(syncedWorld) && !Files.isRegularFile(syncedWorld.resolve("level.dat"))) {
            FileUtils.deleteRecursively(syncedWorld);
        }
    }

    private Path findMostRecentWorldSave(Path savesRoot, Path preferredSaveDir) {
        try (Stream<Path> dirs = Files.list(savesRoot)) {
            Path newest = null;
            Instant newestTime = Instant.MIN;
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                Path levelDat = d.resolve("level.dat");
                if (!Files.isRegularFile(levelDat)) continue;
                Instant mtime = Files.getLastModifiedTime(levelDat).toInstant();
                if (newest == null || mtime.isAfter(newestTime)) {
                    newest = d;
                    newestTime = mtime;
                }
            }
            if (newest == null) return null;
            if (!Files.isRegularFile(preferredSaveDir.resolve("level.dat"))) {
                return newest;
            }
            Instant prefTime = Files.getLastModifiedTime(preferredSaveDir.resolve("level.dat")).toInstant();
            if (newestTime.isAfter(prefTime.plusSeconds(5))
                    && newestTime.isAfter(sessionStartInstant.minusSeconds(10))) {
                return newest;
            }
            return preferredSaveDir;
        } catch (IOException e) {
            return null;
        }
    }

    private static void syncDirectory(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        Set<String> srcRel = new HashSet<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().equals("session.lock")) continue;
                String rel = FileUtils.toPortable(src.relativize(p));
                srcRel.add(rel);
                Path target = dst.resolve(rel).normalize();
                if (Files.isRegularFile(target)
                        && Files.size(target) == Files.size(p)
                        && Files.getLastModifiedTime(target).equals(Files.getLastModifiedTime(p))) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        try (Stream<Path> walk = Files.walk(dst)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().equals("session.lock")) continue;
                String rel = FileUtils.toPortable(dst.relativize(p));
                if (!srcRel.contains(rel)) {
                    FileUtils.deleteWithRetry(p);
                }
            }
        }
    }

    private static void ensurePauseOnLostFocusDisabled(Path gameDir) {
        Path opts = gameDir.resolve("options.txt");
        try {
            List<String> lines = Files.isRegularFile(opts)
                    ? new ArrayList<>(Files.readAllLines(opts, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("pauseOnLostFocus:")) {
                    lines.set(i, "pauseOnLostFocus:false");
                    found = true;
                }
            }
            if (!found) lines.add("pauseOnLostFocus:false");
            Files.write(opts, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Impossibile aggiornare options.txt: {}", e.getMessage());
        }
    }

    private static boolean hasKubeJsMod(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return false;
        try (Stream<Path> s = Files.list(modsDir)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("kubejs") && n.endsWith(".jar");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void injectKubeJsAutoLanIfPresent(Path gameDir, int port) {
        Path kubejsDir = gameDir.resolve("kubejs");
        if (!Files.isDirectory(kubejsDir) && !hasKubeJsMod(gameDir.resolve("mods"))) return;
        try {
            Path serverScripts = kubejsDir.resolve("server_scripts");
            Files.createDirectories(serverScripts);
            Path script = serverScripts.resolve("_mcsync_autolan.js");
            String js = """
                    // Auto-generated by mcServer4all (Single-JVM Integrated Host)
                    let _mcsyncLanPublished = false;
                    let _mcsyncTickCounter = 0;
                    function _mcsyncTryPublish(srv) {
                      if (_mcsyncLanPublished || !srv) return;
                      try {
                        if (srv.isDedicatedServer && srv.isDedicatedServer()) {
                          _mcsyncLanPublished = true;
                          return;
                        }
                        if (srv.isPublished && srv.isPublished()) {
                          _mcsyncLanPublished = true;
                          return;
                        }
                        if (srv.runCommandSilent) {
                          srv.runCommandSilent('publish true survival %d');
                        }
                        if (srv.isPublished && srv.isPublished()) {
                          _mcsyncLanPublished = true;
                          console.info('Local game hosted on port %d');
                          return;
                        }
                        if (srv.publishServer) {
                          let gm = srv.getDefaultGameType ? srv.getDefaultGameType() : null;
                          let ok = srv.publishServer(gm, true, %d);
                          if (ok) {
                            _mcsyncLanPublished = true;
                            console.info('Local game hosted on port %d');
                            return;
                          }
                        }
                        if (srv.runCommandSilent) {
                          srv.runCommandSilent('publish');
                        }
                        if (srv.isPublished && srv.isPublished()) {
                          _mcsyncLanPublished = true;
                        }
                      } catch (e) {}
                    }
                    if (typeof PlayerEvents !== 'undefined') {
                      PlayerEvents.loggedIn((event) => {
                        _mcsyncTryPublish(event.server);
                      });
                    }
                    if (typeof ServerEvents !== 'undefined') {
                      ServerEvents.loaded((event) => {
                        _mcsyncTryPublish(event.server);
                      });
                      ServerEvents.tick((event) => {
                        if (!_mcsyncLanPublished && (++_mcsyncTickCounter %% 60 === 0)) {
                          _mcsyncTryPublish(event.server);
                        }
                      });
                    }
                    """.formatted(port, port, port, port);
            Files.writeString(script, js, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Impossibile scrivere _mcsync_autolan.js: {}", e.getMessage());
        }
    }

    private static Set<Long> snapshotJavaPids() {
        Set<Long> pids = new HashSet<>();
        long myPid = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().forEach(ph -> {
            if (ph.pid() == myPid) return;
            ph.info().command().ifPresent(cmd -> {
                String lc = cmd.toLowerCase(Locale.ROOT);
                if (lc.endsWith("java.exe") || lc.endsWith("javaw.exe")
                        || lc.endsWith("/java") || lc.endsWith("\\java")) {
                    pids.add(ph.pid());
                }
            });
        });
        return pids;
    }

    private static Optional<ProcessHandle> findNewMinecraftJvm(Set<Long> preExistingJavaPids, Instant launchedAt) {
        long myPid = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.pid() != myPid && ph.isAlive() && !preExistingJavaPids.contains(ph.pid()))
                .filter(ph -> {
                    Optional<String> cmd = ph.info().command();
                    if (cmd.isEmpty()) return false;
                    String lc = cmd.get().toLowerCase(Locale.ROOT);
                    if (!(lc.endsWith("java.exe") || lc.endsWith("javaw.exe")
                            || lc.endsWith("/java") || lc.endsWith("\\java"))) {
                        return false;
                    }
                    Optional<Instant> start = ph.info().startInstant();
                    return start.isEmpty() || !start.get().isBefore(launchedAt.minusSeconds(5));
                })
                .findFirst();
    }

    /** Starts Prism Launcher directly into the Singleplayer world and tails {@code logs/latest.log}. */
    public Process startSingleJvmHost(ClientLauncher launcher) throws IOException {
        Path gameDir = cfg.clientInstanceDir();
        Path latestLog = gameDir != null ? gameDir.resolve("logs").resolve("latest.log") : null;
        long initialLogOffset = (latestLog != null && Files.isRegularFile(latestLog)) ? Files.size(latestLog) : 0L;
        Set<Long> preExistingJavaPids = snapshotJavaPids();
        Instant launchedAt = Instant.now();
        this.sessionStartInstant = launchedAt;

        this.clientProcess = launcher.launchSingleplayerHost(cfg.worldFolderName());

        Thread tailThread = new Thread(() -> tailClientLog(latestLog, initialLogOffset), "integrated-log-tail");
        tailThread.setDaemon(true);
        tailThread.start();

        clientProcess.onExit().thenAccept(p -> {
            if (stopping) return;
            long livedSeconds = Duration.between(launchedAt, Instant.now()).getSeconds();
            if (livedSeconds < 30 && p.exitValue() == 0) {
                // Prism Launcher single-instance IPC: prism forwarded --launch to an existing Prism window and exited!
                Thread ipcMonitor = new Thread(() -> monitorDelegatedJvm(preExistingJavaPids, launchedAt, latestLog),
                        "prism-ipc-monitor");
                ipcMonitor.setDaemon(true);
                ipcMonitor.start();
                return;
            }
            stopping = true;
            closeBridge();
            readyLatch.countDown();
            listener.onServerExited(p.exitValue(), true);
        });
        return clientProcess;
    }

    private void monitorDelegatedJvm(Set<Long> preExistingJavaPids, Instant launchedAt, Path latestLog) {
        log.info("Prism Launcher ha delegato l'avvio all'istanza già aperta: attendo il processo JVM di Minecraft ...");
        ProcessHandle mcJvm = null;
        for (int i = 0; i < 45 && !stopping; i++) {
            Optional<ProcessHandle> found = findNewMinecraftJvm(preExistingJavaPids, launchedAt);
            if (found.isPresent()) {
                mcJvm = found.get();
                this.delegatedMcJvm = mcJvm;
                log.info("Rilevato processo JVM di Minecraft (PID {}) — monitoraggio sessione attivo.", mcJvm.pid());
                break;
            }
            FileUtils.sleepQuietly(1000L);
        }
        if (mcJvm != null) {
            while (!stopping && mcJvm.isAlive()) {
                FileUtils.sleepQuietly(1000L);
            }
        } else if (latestLog != null) {
            // Fallback: wait while latest.log is actively modified after launchedAt
            while (!stopping) {
                try {
                    if (Files.isRegularFile(latestLog)) {
                        Instant mtime = Files.getLastModifiedTime(latestLog).toInstant();
                        if (mtime.isAfter(launchedAt) && Duration.between(mtime, Instant.now()).getSeconds() < 120) {
                            FileUtils.sleepQuietly(2000L);
                            continue;
                        }
                    }
                } catch (IOException ignored) {
                }
                break;
            }
        }
        if (!stopping) {
            stopping = true;
            closeBridge();
            readyLatch.countDown();
            listener.onServerExited(0, true);
        }
    }

    public boolean awaitReady(Duration timeout) throws InterruptedException {
        boolean ok = readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return ok && readyAt != null && isRunning();
    }

    public boolean isRunning() {
        if (stopping) return false;
        Process p = clientProcess;
        if (p != null && p.isAlive()) return true;
        ProcessHandle del = delegatedMcJvm;
        return del != null && del.isAlive();
    }

    public Instant readyAt() {
        return readyAt;
    }

    private void notifyReadyOnce() {
        if (readyAt == null) {
            readyAt = Instant.now();
            readyLatch.countDown();
            Runnable cb = onReadyCallback;
            if (cb != null) {
                onReadyCallback = null;
                try {
                    cb.run();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void tailClientLog(Path latestLog, long startOffset) {
        if (latestLog == null) {
            notifyReadyOnce();
            return;
        }
        long offset = startOffset;
        boolean sawWorldStart = false;
        while (!stopping) {
            try {
                if (Files.isRegularFile(latestLog)) {
                    long len = Files.size(latestLog);
                    if (len < offset) offset = 0; // log rotated on client boot
                    if (len > offset) {
                        try (RandomAccessFile raf = new RandomAccessFile(latestLog.toFile(), "r")) {
                            raf.seek(offset);
                            String rawLine;
                            while ((rawLine = raf.readLine()) != null) {
                                String line = new String(rawLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                                listener.onConsoleLine(line);
                                processLogLine(line);
                                if (readyAt != null) sawWorldStart = true;
                                if (sawWorldStart && INTEGRATED_STOPPED.matcher(line).matches()) {
                                    log.info("Rilevata uscita dal mondo al Menu Principale (Fast Handover): avvio sync immediato!");
                                    stopping = true;
                                    closeBridge();
                                    listener.onServerExited(0, true);
                                    return;
                                }
                            }
                            offset = raf.getFilePointer();
                        }
                    }
                }
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Log tail error: {}", e.getMessage());
            }
        }
    }

    private void processLogLine(String line) {
        Matcher lanMatcher = LAN_PORT_PATTERN.matcher(line);
        if (lanMatcher.matches()) {
            int port = Integer.parseInt(lanMatcher.group(1));
            onLanPublished(port);
            return;
        }
        if (readyAt == null && INTEGRATED_READY.matcher(line).matches()) {
            log.info("IntegratedServer avviato nel Client! Pubblicazione LAN su porta {} ...", cfg.serverPort());
            notifyReadyOnce();
            return;
        }
        Matcher jl = ServerProcessController.JOIN_LEAVE.matcher(line);
        if (jl.matches()) {
            String name = jl.group(1);
            if ("joined".equals(jl.group(2))) {
                onlinePlayers.add(name);
                listener.onPlayerJoined(name, onlinePlayers.size());
            } else {
                onlinePlayers.remove(name);
                listener.onPlayerLeft(name, onlinePlayers.size());
            }
        }
    }

    private void onLanPublished(int port) {
        this.internalLanPort = port;
        notifyReadyOnce();
        if (port != cfg.serverPort()) {
            startTcpPortBridge(cfg.serverPort(), port);
        } else {
            log.info("IntegratedServer pubblicato direttamente sulla porta {}", port);
        }
    }

    /**
     * Bridges incoming connections on {@code externalPort} (e.g. 25565 on Tailscale) to {@code internalLanPort}
     * on 127.0.0.1 when Minecraft's "Open to LAN" picks a random ephemeral port.
     */
    private synchronized void startTcpPortBridge(int externalPort, int targetLanPort) {
        closeBridge();
        try {
            tcpBridgeServer = new ServerSocket();
            tcpBridgeServer.setReuseAddress(true);
            tcpBridgeServer.bind(new InetSocketAddress(externalPort));
            log.info("Bridge TCP attivo: {}:{} -> 127.0.0.1:{} (LAN IntegratedServer)",
                    cfg.localTailscaleIp(), externalPort, targetLanPort);
            Thread acceptThread = new Thread(() -> {
                while (!stopping && tcpBridgeServer != null && !tcpBridgeServer.isClosed()) {
                    try {
                        Socket clientSock = tcpBridgeServer.accept();
                        Socket lanSock = new Socket("127.0.0.1", targetLanPort);
                        pipeAsync(clientSock, lanSock);
                        pipeAsync(lanSock, clientSock);
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }, "lan-tcp-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            log.warn("Impossibile aprire il bridge TCP sulla porta {}: {}", externalPort, e.getMessage());
        }
    }

    private static void pipeAsync(Socket inSock, Socket outSock) {
        Thread t = new Thread(() -> {
            try (InputStream in = inSock.getInputStream(); OutputStream out = outSock.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
            } finally {
                try {
                    inSock.close();
                } catch (IOException ignored) {
                }
                try {
                    outSock.close();
                } catch (IOException ignored) {
                }
            }
        }, "lan-bridge-pipe");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void closeBridge() {
        if (tcpBridgeServer != null) {
            try {
                tcpBridgeServer.close();
            } catch (IOException ignored) {
            }
            tcpBridgeServer = null;
        }
    }

    public void stopGracefully() {
        stopping = true;
        closeBridge();
    }
}
