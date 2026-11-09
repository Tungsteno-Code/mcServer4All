package com.mcsync.core;

import com.mcsync.config.Config;
import com.mcsync.util.FileUtils;
import com.mcsync.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Launches the modded Minecraft client (typically through the Prism Launcher CLI).
 *
 * <p>Supported placeholders in {@code clientLaunchCommand}: {@code {serverAddress}} (host:port),
 * {@code {host}}, {@code {port}}, {@code {playerId}}.</p>
 *
 * <p>Windows example:
 * {@code ["%LOCALAPPDATA%\\Programs\\PrismLauncher\\prismlauncher.exe", "--launch", "MyPack", "--server", "{serverAddress}"]}</p>
 */
public final class ClientLauncher {

    private static final Logger log = LoggerFactory.getLogger(ClientLauncher.class);

    private final Config cfg;

    public ClientLauncher(Config cfg) {
        this.cfg = cfg;
    }

    public boolean isConfigured() {
        return !cfg.clientLaunchCommand().isEmpty();
    }

    /**
     * Starts the client pointed at {@code host:port}. Output goes to {@code .mcsync/logs/client.log}
     * so a chatty launcher can never block on a full pipe.
     */
    public Process launch(String host, int port) throws IOException {
        String address = port == 25565 ? host : host + ":" + port;
        List<String> cmd = ProcessUtils.prepareCommand(cfg.clientLaunchCommand(), Map.of(
                "serverAddress", address,
                "host", host,
                "port", Integer.toString(port),
                "playerId", cfg.playerId()));
        cmd = new java.util.ArrayList<>(cmd);
        Path configured = Path.of(cmd.get(0));
        if (Files.isDirectory(configured)) {
            // Common mistake: the Prism Launcher *folder* instead of prismlauncher.exe
            Path exe = findLauncherExecutable(configured);
            if (exe == null) {
                throw new IOException("clientLaunchCommand points to the folder " + configured
                        + " instead of the launcher executable (e.g. prismlauncher.exe)");
            }
            log.warn("clientLaunchCommand points to a folder: using {}", exe);
            cmd.set(0, exe.toString());
        }
        Path logFile = cfg.stateDir().resolve("logs").resolve("client.log");
        Files.createDirectories(logFile.getParent());
        log.info("Launching Minecraft client -> {}", address);
        log.debug("Client command: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Path exe = Path.of(cmd.get(0));
        if (exe.isAbsolute() && exe.getParent() != null && Files.isDirectory(exe.getParent())) {
            pb.directory(exe.getParent().toFile());
        }
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            return p;
        } catch (IOException e) {
            throw new IOException("Cannot start the Minecraft client (" + cmd.get(0) + "): " + e.getMessage()
                    + (FileUtils.IS_WINDOWS ? " - check the path to prismlauncher.exe in clientLaunchCommand" : ""), e);
        }
    }

    /**
     * Starts the client in Singleplayer / IntegratedServer mode for Single-JVM hosting:
     * removes {@code --server {serverAddress}} from the Prism command so the Host doesn't try to connect
     * to a dedicated server before the IntegratedServer is started, and adds {@code --world <worldFolderName>}
     * when the world save already exists on disk so Prism boots straight into the Singleplayer world.
     */
    public Process launchSingleplayerHost(String worldFolderName) throws IOException {
        List<String> raw = new java.util.ArrayList<>(cfg.clientLaunchCommand());
        int srvIdx = raw.indexOf("--server");
        if (srvIdx >= 0) {
            raw.remove(srvIdx);
            if (srvIdx < raw.size()) raw.remove(srvIdx);
        }
        Path gameDir = cfg.clientInstanceDir();
        boolean worldSaveExists = gameDir != null
                && Files.isRegularFile(gameDir.resolve("saves").resolve(worldFolderName).resolve("level.dat"));
        if (worldSaveExists && !raw.contains("--world") && !raw.contains("-w")) {
            raw.add("--world");
            raw.add(worldFolderName);
        }
        List<String> cmd = ProcessUtils.prepareCommand(raw, Map.of(
                "worldFolderName", worldFolderName,
                "playerId", cfg.playerId()));
        cmd = new java.util.ArrayList<>(cmd);
        Path configured = Path.of(cmd.get(0));
        if (Files.isDirectory(configured)) {
            Path exe = findLauncherExecutable(configured);
            if (exe == null) {
                throw new IOException("clientLaunchCommand points to the folder " + configured
                        + " instead of the launcher executable (e.g. prismlauncher.exe)");
            }
            cmd.set(0, exe.toString());
        }
        Path logFile = cfg.stateDir().resolve("logs").resolve("client.log");
        Files.createDirectories(logFile.getParent());
        log.info("Avvio Minecraft in modalità Single-JVM Host (Mondo: '{}', auto-join: {}) ...",
                worldFolderName, worldSaveExists ? "SI (--world)" : "NO (crea o apri il mondo da Giocatore Singolo)");
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Path exe = Path.of(cmd.get(0));
        if (exe.isAbsolute() && exe.getParent() != null && Files.isDirectory(exe.getParent())) {
            pb.directory(exe.getParent().toFile());
        }
        Process p = pb.start();
        p.getOutputStream().close();
        return p;
    }

    /** Finds the Prism Launcher executable inside a folder (portable install). */
    public static Path findLauncherExecutable(Path dir) {
        for (String n : List.of("prismlauncher.exe", "PrismLauncher.exe", "prismlauncher", "PrismLauncher",
                "PrismLauncher.AppImage", "Contents/MacOS/prismlauncher", "bin/prismlauncher")) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
