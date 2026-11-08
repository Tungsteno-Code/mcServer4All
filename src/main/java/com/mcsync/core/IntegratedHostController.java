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
    private volatile ServerSocket tcpBridgeServer;
    private volatile int internalLanPort = -1;
    private volatile boolean stopping;
    private volatile Instant readyAt;

    public IntegratedHostController(Config cfg, ServerProcessController.Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
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
        Path gameDir = cfg.clientInstanceDir();
        if (gameDir == null) {
            throw new IOException("clientInstanceDirectory non configurata per Single-JVM Host");
        }
        Files.createDirectories(gameDir.resolve("saves"));
        Path saveDir = clientWorldSaveDir();
        Path syncedWorld = cfg.worldDir();

        if (Files.isDirectory(syncedWorld) && !syncedWorld.equals(saveDir)) {
            syncDirectory(syncedWorld, saveDir);
        } else {
            Files.createDirectories(saveDir);
        }
        LevelDatCleaner.cleanWorldDir(saveDir);
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
            // Note: Prism passes ExtraArgs/MinecraftArgs or user can click Singleplayer -> world
            boolean hasOverride = lines.stream().anyMatch(l -> l.startsWith("OverrideMemory="));
            if (!hasOverride) {
                lines.add("OverrideMemory=true");
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
        Path saveDir = clientWorldSaveDir();
        Path syncedWorld = cfg.worldDir();
        if (Files.isDirectory(saveDir) && !saveDir.equals(syncedWorld)) {
            syncDirectory(saveDir, syncedWorld);
        }
        LevelDatCleaner.cleanWorldDir(syncedWorld);
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

    private static void injectKubeJsAutoLanIfPresent(Path gameDir, int port) {
        Path kubejsDir = gameDir.resolve("kubejs");
        if (!Files.isDirectory(kubejsDir)) return;
        try {
            Path serverScripts = kubejsDir.resolve("server_scripts");
            Files.createDirectories(serverScripts);
            Path script = serverScripts.resolve("_mcsync_autolan.js");
            String js = """
                    // Auto-generated by mcServer4all (Single-JVM Integrated Host)
                    if (typeof ServerEvents !== 'undefined') {
                      ServerEvents.loaded(event => {
                        try {
                          let srv = event.server;
                          if (srv && !srv.isDedicatedServer() && !srv.isPublished()) {
                            srv.publishServer(srv.getDefaultGameType(), true, %d);
                            console.info('Local game hosted on port %d');
                          }
                        } catch (e) {}
                      });
                    }
                    """.formatted(port, port);
            Files.writeString(script, js, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Impossibile scrivere _mcsync_autolan.js: {}", e.getMessage());
        }
    }

    /** Starts Prism Launcher directly into the Singleplayer world and tails {@code logs/latest.log}. */
    public Process startSingleJvmHost(ClientLauncher launcher) throws IOException {
        Path gameDir = cfg.clientInstanceDir();
        Path latestLog = gameDir != null ? gameDir.resolve("logs").resolve("latest.log") : null;
        long initialLogOffset = (latestLog != null && Files.isRegularFile(latestLog)) ? Files.size(latestLog) : 0L;

        this.clientProcess = launcher.launchSingleplayerHost(cfg.worldFolderName());

        Thread tailThread = new Thread(() -> tailClientLog(latestLog, initialLogOffset), "integrated-log-tail");
        tailThread.setDaemon(true);
        tailThread.start();

        clientProcess.onExit().thenAccept(p -> {
            stopping = true;
            closeBridge();
            readyLatch.countDown();
            listener.onServerExited(p.exitValue(), true);
        });
        return clientProcess;
    }

    public boolean awaitReady(Duration timeout) throws InterruptedException {
        boolean ok = readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return ok && readyAt != null && isRunning();
    }

    public boolean isRunning() {
        Process p = clientProcess;
        return p != null && p.isAlive() && !stopping;
    }

    public Instant readyAt() {
        return readyAt;
    }

    private void tailClientLog(Path latestLog, long startOffset) {
        if (latestLog == null) {
            readyAt = Instant.now();
            readyLatch.countDown();
            return;
        }
        long offset = startOffset;
        boolean sawWorldStart = false;
        while (!stopping && clientProcess != null && clientProcess.isAlive()) {
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
            readyAt = Instant.now();
            log.info("IntegratedServer avviato nel Client! Pubblicazione LAN su porta {} ...", cfg.serverPort());
            readyLatch.countDown();
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
        if (readyAt == null) {
            readyAt = Instant.now();
            readyLatch.countDown();
        }
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
