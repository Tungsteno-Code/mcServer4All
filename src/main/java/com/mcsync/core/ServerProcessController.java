package com.mcsync.core;

import com.mcsync.config.Config;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.util.FileUtils;
import com.mcsync.util.NetUtils;
import com.mcsync.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the Minecraft server subprocess.
 *
 * <ul>
 *     <li>Enforces {@code server-ip}, {@code online-mode=true}, {@code white-list=true} in
 *     {@code server.properties} before boot.</li>
 *     <li>Reads the console asynchronously: readiness ({@code Done (x.xxxs)!}), join/leave
 *     events, {@code list} responses, OutOfMemoryError and tick watchdog alerts.</li>
 *     <li>Tracks online players via log parsing and a periodic {@code list} poll.</li>
 *     <li>Player-aware deferred shutdown: when the local client exits the controller switches to
 *     {@link HostMode#DAEMON_HOSTING} and only stops after {@code idleShutdownSeconds} without players.</li>
 *     <li>{@link #stopGracefully()}: {@code save-all flush} + {@code stop}, bounded wait, then
 *     process-tree kill.</li>
 * </ul>
 */
public final class ServerProcessController {

    private static final Logger log = LoggerFactory.getLogger(ServerProcessController.class);

    static final Pattern READY = Pattern.compile(".*Done \\([0-9.,]+s\\)!.*");
    static final Pattern JOIN_LEAVE = Pattern.compile("^\\[.*\\]: ([a-zA-Z0-9_]{2,16}) (joined|left) the game$");
    static final Pattern LIST = Pattern.compile(
            ".*There are (\\d+)(?: of a max(?: of)? |/)(\\d+) players online:?(.*)$");
    static final Pattern TICK_WATCHDOG = Pattern.compile(
            ".*A single server tick took ([0-9.,]+) seconds \\(should be max ([0-9.,]+)\\).*");
    static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d?]*[A-Za-z]");
    private static final String OOM = "java.lang.OutOfMemoryError";

    /** Lifecycle mode while the server is running. */
    public enum HostMode {
        /** Local client is running: never auto-stop. */
        HOSTING,
        /** Local client closed: stop as soon as nobody is online for idleShutdownSeconds. */
        DAEMON_HOSTING
    }

    /** Callbacks invoked from the console reader / monitor threads. */
    public interface Listener {
        default void onPlayerJoined(String name, int online) {
        }

        default void onPlayerLeft(String name, int online) {
        }

        default void onOutOfMemory() {
        }

        default void onTickWatchdog(String seconds) {
        }

        default void onIdleShutdown() {
        }

        default void onHostModeChanged(HostMode mode) {
        }

        default void onServerExited(int exitCode, boolean expected) {
        }

        /** Every console line of the server (ANSI codes stripped). */
        default void onConsoleLine(String line) {
        }
    }

    private final Config cfg;
    private final Listener listener;
    private final Set<String> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "server-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile HostMode mode = HostMode.HOSTING;
    private volatile boolean stopping;
    private volatile boolean eulaMissing;
    private volatile boolean oomHandled;
    private volatile boolean firstJoinSeen;
    private volatile Instant readyAt;
    private volatile Instant idleSince;
    private volatile boolean idleFired;

    public ServerProcessController(Config cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    // ------------------------------------------------------------------------------------------
    // Pre-boot preparation
    // ------------------------------------------------------------------------------------------

    /** Rewrites server.properties keeping every unrelated line untouched. */
    public void prepareServerProperties() throws IOException {
        Path file = cfg.serverDir().resolve("server.properties");
        Map<String, String> enforced = new LinkedHashMap<>();
        enforced.put("server-ip", cfg.serverBindAddress());
        enforced.put("server-port", Integer.toString(cfg.serverPort()));
        enforced.put("online-mode", "true");
        enforced.put("white-list", cfg.whitelistPlayers().isEmpty() ? "false" : "true");
        enforced.put("level-name", cfg.worldFolderName());

        // ISO-8859-1 round-trips every byte, so non-ASCII MOTDs are preserved whatever their encoding.
        List<String> lines = Files.exists(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.ISO_8859_1))
                : new ArrayList<>(List.of("#Minecraft server properties (created by mc-sync-launcher)"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
            if (enforced.containsKey(key)) {
                lines.set(i, key + "=" + enforced.get(key));
                seen.add(key);
            }
        }
        enforced.forEach((k, v) -> {
            if (!seen.contains(k)) lines.add(k + "=" + v);
        });
        Files.write(file, lines, StandardCharsets.ISO_8859_1);
        log.info("server.properties: server-ip={}, port={}, online-mode=true, white-list=true (local client -> {})",
                cfg.serverBindAll() ? "<all interfaces>" : cfg.localTailscaleIp(), cfg.serverPort(),
                cfg.effectiveClientAddress());

        if (cfg.acceptEula()) {
            Files.writeString(cfg.serverDir().resolve("eula.txt"),
                    "# Accepted via mc-sync-launcher (acceptEula=true): https://aka.ms/MinecraftEULA" + System.lineSeparator()
                            + "eula=true" + System.lineSeparator(), StandardCharsets.ISO_8859_1);
        }
        Path whitelist = cfg.serverDir().resolve("whitelist.json");
        if (cfg.whitelistPlayers().isEmpty()
                && (!Files.exists(whitelist) || Files.readString(whitelist, StandardCharsets.UTF_8).replaceAll("\\s", "").equals("[]"))) {
            log.warn("white-list=true but whitelist.json is empty and 'whitelistPlayers' is not set: nobody will be able to join!");
        }
    }

    /**
     * Kills a server left running by a previous launcher that died (Windows does not kill child
     * processes together with their parent).
     */
    public void killOrphanedServer() {
        Path pidFile = cfg.stateDir().resolve("server.pid");
        try {
            if (!Files.exists(pidFile)) return;
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            Optional<ProcessHandle> ph = ProcessHandle.of(pid);
            if (ph.isPresent() && ph.get().isAlive()) {
                String command = ph.get().info().command().orElse("");
                String lower = command.toLowerCase(Locale.ROOT);
                if (lower.contains("java") || lower.contains("cmd.exe") || lower.contains("bash") || lower.endsWith("sh")) {
                    log.warn("A server process from a previous session is still running (PID {}, {}): terminating it", pid, command);
                    ph.get().descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.get().destroyForcibly();
                    ph.get().onExit().get(20, TimeUnit.SECONDS);
                } else if (!command.isEmpty()) {
                    log.debug("PID {} was reused by '{}', ignoring", pid, command);
                }
            }
            Files.deleteIfExists(pidFile);
        } catch (Exception e) {
            log.warn("Orphan server check failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Start & readiness
    // ------------------------------------------------------------------------------------------

    public void start() throws IOException {
        if (process != null) throw new IllegalStateException("Server already started");
        if (NetUtils.tcpProbe(cfg.localProbeAddress(), cfg.serverPort(), 1000)) {
            throw new IOException("Port " + cfg.serverPort() + " on " + cfg.localProbeAddress()
                    + " is already in use: is another Minecraft server running?");
        }
        // Pre-flight: repair "java -jar run.bat", missing jars, wrong platform scripts.
        List<String> configured = ServerProfileDetector.repairCommand(cfg.serverStartCommand(), cfg.serverDir());
        if (configured.isEmpty()) {
            throw new IOException("No server start command configured and no server detected in " + cfg.serverDir()
                    + " (detected: " + ServerProfileDetector.detect(cfg.serverDir()).profile().label + ")");
        }
        Path javaHome = cfg.serverJavaHome().isBlank() ? null : Path.of(Config.expandHome(cfg.serverJavaHome()));
        String first = configured.get(0);
        if (javaHome != null && (first.equalsIgnoreCase("java") || first.equalsIgnoreCase("java.exe"))) {
            Path bin = javaHome.resolve("bin").resolve(FileUtils.IS_WINDOWS ? "java.exe" : "java");
            if (Files.isRegularFile(bin)) {
                configured = new ArrayList<>(configured);
                configured.set(0, bin.toString());
            }
        }
        List<String> cmd = ProcessUtils.prepareCommand(configured, Map.of(
                "serverDirectory", cfg.serverDir().toString(),
                "worldFolderName", cfg.worldFolderName()));
        log.info("Starting server in {}", cfg.serverDir());
        log.debug("Server command: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cfg.serverDir().toFile())
                .redirectErrorStream(true);
        ProcessUtils.applyJavaHome(pb, javaHome);
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Cannot start the server with " + cmd + ": " + e.getMessage(), e);
        }
        Files.createDirectories(cfg.stateDir());
        Files.writeString(cfg.stateDir().resolve("server.pid"), Long.toString(process.pid()));
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), ProcessUtils.childCharset()));

        Thread reader = new Thread(this::readConsole, "server-console");
        reader.setDaemon(true);
        reader.start();

        process.onExit().thenAccept(p -> {
            int code = p.exitValue();
            readyLatch.countDown();
            try {
                Files.deleteIfExists(cfg.stateDir().resolve("server.pid"));
            } catch (IOException ignored) {
                // best effort
            }
            log.info("Server process exited with code {}", code);
            listener.onServerExited(code, stopping);
        });
    }

    /**
     * Waits for the readiness line.
     *
     * @return true if ready, false on timeout or premature exit
     */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        boolean signalled = readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (eulaMissing) {
            log.error("The server refused to start because the EULA is not accepted. "
                    + "Set \"acceptEula\": true in config.json or edit eula.txt.");
        }
        return signalled && readyAt != null && isRunning();
    }

    private void readConsole() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), ProcessUtils.childCharset()))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                String line = ANSI.matcher(raw).replaceAll("").stripTrailing();
                if (cfg.echoServerOutput()) System.out.println("[server] " + line);
                try {
                    listener.onConsoleLine(line);
                } catch (RuntimeException e) {
                    log.debug("Console listener error: {}", e.getMessage());
                }
                handleLine(line);
            }
        } catch (IOException e) {
            if (!stopping) log.debug("Server console closed: {}", e.getMessage());
        }
    }

    public void handleLine(String line) {
        if (readyAt == null && READY.matcher(line).matches()) {
            readyAt = Instant.now();
            log.info("Server is ready");
            readyLatch.countDown();
            return;
        }
        Matcher jl = JOIN_LEAVE.matcher(line);
        if (jl.matches()) {
            String name = jl.group(1);
            if ("joined".equals(jl.group(2))) {
                onlinePlayers.add(name);
                firstJoinSeen = true;
                log.info("Player joined: {} ({} online)", name, onlinePlayers.size());
                listener.onPlayerJoined(name, onlinePlayers.size());
            } else {
                onlinePlayers.remove(name);
                log.info("Player left: {} ({} online)", name, onlinePlayers.size());
                listener.onPlayerLeft(name, onlinePlayers.size());
            }
            return;
        }
        Matcher lm = LIST.matcher(line);
        if (lm.matches()) {
            reconcilePlayers(Integer.parseInt(lm.group(1)), lm.group(3));
            return;
        }
        if (line.contains(OOM)) {
            handleOutOfMemory();
            return;
        }
        Matcher tw = TICK_WATCHDOG.matcher(line);
        if (tw.matches()) {
            log.error("Tick watchdog: a single tick took {} s", tw.group(1));
            listener.onTickWatchdog(tw.group(1));
            return;
        }
        if (line.contains("You need to agree to the EULA")) {
            eulaMissing = true;
        }
    }

    private void reconcilePlayers(int count, String namesPart) {
        Set<String> names = new HashSet<>();
        if (namesPart != null) {
            for (String n : namesPart.split(",")) {
                String t = n.trim();
                int paren = t.indexOf(' ');
                if (paren > 0) t = t.substring(0, paren); // "name (uuid)" from 'list uuids'
                if (t.matches("[a-zA-Z0-9_]{2,16}")) names.add(t);
            }
        }
        if (count == 0) {
            onlinePlayers.clear();
        } else if (names.size() == count) {
            onlinePlayers.retainAll(names);
            onlinePlayers.addAll(names);
            firstJoinSeen = true;
        } else if (onlinePlayers.isEmpty()) {
            // Names on a separate line (old versions): keep a placeholder so the count is right.
            for (int i = 0; i < count; i++) onlinePlayers.add("player#" + i);
            firstJoinSeen = true;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Monitoring: player poll + idle countdown
    // ------------------------------------------------------------------------------------------

    /** Starts the {@code list} poll and the idle countdown evaluator. Call after the server is ready. */
    public void startMonitoring() {
        for (String player : cfg.whitelistPlayers()) {
            sendCommand("whitelist add " + player);
        }
        int poll = cfg.playerPollIntervalSeconds();
        monitor.scheduleWithFixedDelay(() -> {
            if (isRunning() && !stopping) sendCommand("list");
        }, poll, poll, TimeUnit.SECONDS);
        monitor.scheduleWithFixedDelay(this::evaluateIdle, 1, 1, TimeUnit.SECONDS);
    }

    private void evaluateIdle() {
        try {
            if (stopping || idleFired || !isRunning() || mode != HostMode.DAEMON_HOSTING || readyAt == null) {
                idleSince = null;
                return;
            }
            Instant now = Instant.now();
            boolean graceOver = firstJoinSeen
                    || Duration.between(readyAt, now).getSeconds() >= cfg.initialJoinGraceSeconds();
            if (!onlinePlayers.isEmpty() || !graceOver) {
                if (idleSince != null) log.info("Player online again: idle shutdown cancelled");
                idleSince = null;
                return;
            }
            if (idleSince == null) {
                idleSince = now;
                log.info("No players online: shutting down in {}s unless somebody joins", cfg.idleShutdownSeconds());
                return;
            }
            if (Duration.between(idleSince, now).getSeconds() >= cfg.idleShutdownSeconds()) {
                idleFired = true;
                // "say" prefixes the message with "[Server]".
                sendCommand("say No active players detected. Shutting down and syncing...");
                log.info("Idle timeout reached");
                listener.onIdleShutdown();
            }
        } catch (RuntimeException e) {
            log.warn("Idle monitor error: {}", e.getMessage());
        }
    }

    /**
     * Watches the local client process: when it exits, the controller switches to
     * {@link HostMode#DAEMON_HOSTING} instead of stopping the server.
     */
    public void watchClient(Process client) {
        Instant started = Instant.now();
        client.onExit().thenAccept(p -> {
            long secs = Duration.between(started, Instant.now()).getSeconds();
            if (secs < 30) {
                log.info("Client launcher returned after {}s (probably handed over to an already open Prism window). "
                        + "The server stays up while players are online.", secs);
            } else {
                log.info("Local Minecraft client closed");
            }
            setMode(HostMode.DAEMON_HOSTING);
        });
    }

    public void setMode(HostMode newMode) {
        if (mode == newMode) return;
        mode = newMode;
        log.info("Host mode: {}", newMode);
        if (newMode == HostMode.DAEMON_HOSTING && isRunning()) {
            sendCommand("list");
        }
        listener.onHostModeChanged(newMode);
    }

    public HostMode mode() {
        return mode;
    }

    // ------------------------------------------------------------------------------------------
    // Commands & shutdown
    // ------------------------------------------------------------------------------------------

    public synchronized boolean sendCommand(String command) {
        BufferedWriter w = stdin;
        if (w == null || !isRunning()) return false;
        try {
            w.write(command);
            w.write("\n");
            w.flush();
            return true;
        } catch (IOException e) {
            log.debug("Cannot write to server stdin: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Sends {@code save-all flush} then {@code stop} and waits up to {@code stopTimeoutSeconds}
     * (60 s by default) before killing the process tree.
     *
     * @return true if the server exited on its own (world saved cleanly)
     */
    public boolean stopGracefully() {
        Process p = process;
        if (p == null) return true;
        stopping = true;
        if (!p.isAlive()) return p.exitValue() == 0 || readyAt != null;
        log.info("Stopping server (save-all flush + stop) ...");
        sendCommand("save-all flush");
        FileUtils.sleepQuietly(2000);
        sendCommand("stop");
        try {
            if (p.waitFor(cfg.stopTimeoutSeconds(), TimeUnit.SECONDS)) {
                closeStdin();
                log.info("Server stopped cleanly");
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.error("Server did not stop within {}s: killing process tree", cfg.stopTimeoutSeconds());
        ProcessUtils.killTree(p);
        return false;
    }

    /** Emergency recovery for OutOfMemoryError: {@code stop}, 10 s, then destroyForcibly. */
    private void handleOutOfMemory() {
        if (oomHandled) return;
        oomHandled = true;
        log.error("java.lang.OutOfMemoryError detected: emergency stop");
        listener.onOutOfMemory();
        Thread t = new Thread(() -> {
            stopping = true;
            sendCommand("stop");
            Process p = process;
            try {
                if (p != null && !p.waitFor(10, TimeUnit.SECONDS)) {
                    log.error("Server unresponsive after OOM: destroyForcibly()");
                    ProcessUtils.killTree(p);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "oom-recovery");
        t.setDaemon(true);
        t.start();
    }

    private void closeStdin() {
        try {
            BufferedWriter w = stdin;
            if (w != null) w.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Handle of the server process (the wrapper cmd.exe/bash when started through a script). */
    public Optional<ProcessHandle> processHandle() {
        Process p = process;
        return p == null ? Optional.empty() : Optional.of(p.toHandle());
    }

    public boolean wasStarted() {
        return process != null;
    }

    public boolean isReady() {
        return readyAt != null;
    }

    public Instant readyAt() {
        return readyAt;
    }

    public Set<String> onlinePlayers() {
        return Set.copyOf(onlinePlayers);
    }

    public void shutdownMonitor() {
        monitor.shutdownNow();
    }
}
