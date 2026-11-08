package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.model.ModsLock;
import com.mcsync.model.WorldRecipe;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import com.mcsync.util.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Idempotent per-world server installation from the shared recipe + lock. The server pack is never
 * distributed: every host builds the very same server locally. If the recorded
 * {@code installedServerRecipeHash} matches, nothing at all is done.
 */
public final class ServerInstaller {

    private static final Logger log = LoggerFactory.getLogger(ServerInstaller.class);

    /** Downloads a URL to a file (replaceable in tests). */
    public interface Fetcher {
        void fetch(String url, Path target) throws IOException;
    }

    /** Runs an installer process in a directory (replaceable in tests). */
    public interface Runner {
        int run(List<String> command, Path dir, Path javaHome) throws IOException, InterruptedException;
    }

    /** @param changed true if loader or files were (re)installed */
    public record Result(List<String> startCommand, boolean changed) {
    }

    private final LockMaterializer materializer;
    private final Supplier<Path> javaBin;
    private final Fetcher fetcher;
    private final Runner runner;

    public ServerInstaller(LockMaterializer materializer, Supplier<Path> javaBin) {
        this(materializer, javaBin, (url, target) -> Http.download(url, Map.of(), target), ServerInstaller::runProcess);
    }

    public ServerInstaller(LockMaterializer materializer, Supplier<Path> javaBin, Fetcher fetcher, Runner runner) {
        this.materializer = materializer;
        this.javaBin = javaBin;
        this.fetcher = fetcher;
        this.runner = runner;
    }

    /** Hash of everything that defines the local server build. */
    public static String recipeHash(WorldRecipe r, ModsLock lock) {
        String s = String.join("|", r.minecraft() == null ? "" : r.minecraft(),
                r.loader() == null ? "" : r.loader().type() + ":" + r.loader().version(),
                Integer.toString(r.javaVersion()), Integer.toString(r.ramMinMb()), Integer.toString(r.ramMaxMb()),
                String.join(" ", r.jvmArgs()), lock.contentHash());
        return WorldStore.sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public Result ensure(WorldRecipe recipe, ModsLock lock, Path serverDir, LocalStateStore state) throws IOException {
        Files.createDirectories(serverDir);
        String hash = recipeHash(recipe, lock);
        List<String> existing = startCommand(recipe, serverDir);
        if (hash.equals(state.get().installedServerRecipeHash()) && !existing.isEmpty()) {
            log.info("Server già costruito dalla ricetta (modpack v{}): nessuna azione", lock.modpackVersion());
            return new Result(existing, false);
        }
        Path marker = serverDir.resolve(".mcsync").resolve("loader.txt");
        String wanted = recipe.loader().type() + "|" + recipe.minecraft() + "|" + recipe.loader().version();
        String have = Files.isRegularFile(marker) ? Files.readString(marker).trim() : "";
        if (existing.isEmpty() || (!have.isEmpty() && !wanted.equals(have))) {
            installLoader(recipe, serverDir);
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, wanted);
        writeJvmArgs(recipe, serverDir);
        sanitizeBatchScripts(serverDir);
        LockMaterializer.Result r = materializer.materializeServer(lock, serverDir, true);
        log.info("Server pronto: {} mod scaricate, {} collegate, {} rimosse, {} file server", r.downloaded(), r.linked(),
                r.removed(), r.configs());
        List<String> cmd = startCommand(recipe, serverDir);
        if (cmd.isEmpty()) throw new IOException("Installazione del server completata ma nessun comando di avvio trovato in " + serverDir);
        state.update(s -> s.withInstalledRecipeHash(hash).withAppliedLock("server", lock.contentHash(), lock.modpackVersion()));
        return new Result(cmd, true);
    }

    // ------------------------------------------------------------------------------------------
    // Loader installation
    // ------------------------------------------------------------------------------------------

    static String forgeInstallerUrl(String mc, String version) {
        String v = mc + "-" + version;
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + v + "/forge-" + v + "-installer.jar";
    }

    static String neoforgeInstallerUrl(String version) {
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-installer.jar";
    }

    private void installLoader(WorldRecipe recipe, Path serverDir) throws IOException {
        ModsLock.Loader l = recipe.loader();
        Path java = javaBin.get();
        Path javaHome = java != null && java.getParent() != null && java.getParent().getParent() != null
                ? java.toAbsolutePath().getParent().getParent() : null;
        String javaExe = java == null ? "java" : java.toString();
        log.info("Installazione del server {} {} per Minecraft {} ...", l.type(), l.version(), recipe.minecraft());
        try {
            switch (l.type()) {
                case "forge", "neoforge" -> {
                    Path installer = serverDir.resolve(l.type() + "-installer.jar");
                    fetcher.fetch("forge".equals(l.type()) ? forgeInstallerUrl(recipe.minecraft(), l.version())
                            : neoforgeInstallerUrl(l.version()), installer);
                    int code = runner.run(List.of(javaExe, "-jar", installer.getFileName().toString(), "--installServer"),
                            serverDir, javaHome);
                    Files.deleteIfExists(installer);
                    if (code != 0) throw new IOException("L'installer di " + l.type() + " è terminato con codice " + code);
                }
                case "fabric" -> {
                    String installerVer = latestStable("https://meta.fabricmc.net/v2/versions/installer", "version");
                    fetcher.fetch("https://meta.fabricmc.net/v2/versions/loader/" + recipe.minecraft() + "/" + l.version()
                            + "/" + installerVer + "/server/jar", serverDir.resolve("fabric-server-launch.jar"));
                }
                case "quilt" -> {
                    Path installer = serverDir.resolve("quilt-installer.jar");
                    String url = latestUrl("https://meta.quiltmc.org/v3/versions/installer");
                    fetcher.fetch(url, installer);
                    int code = runner.run(List.of(javaExe, "-jar", installer.getFileName().toString(), "install", "server",
                            recipe.minecraft(), l.version(), "--download-server", "--install-dir=."), serverDir, javaHome);
                    Files.deleteIfExists(installer);
                    if (code != 0) throw new IOException("L'installer di Quilt è terminato con codice " + code);
                }
                default -> throw new IOException("Mod loader non supportato: " + l.type());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Installazione interrotta", e);
        }
    }

    private static String latestStable(String url, String field) throws IOException {
        String body = Http.getString(url, Map.of());
        if (body == null) throw new IOException("Risposta vuota da " + url);
        for (JsonNode n : Json.mapper().readTree(body)) {
            if (n.path("stable").asBoolean(true)) return n.path(field).asText();
        }
        throw new IOException("Nessuna versione stabile da " + url);
    }

    private static String latestUrl(String url) throws IOException {
        String body = Http.getString(url, Map.of());
        if (body == null) throw new IOException("Risposta vuota da " + url);
        JsonNode arr = Json.mapper().readTree(body);
        if (!arr.isArray() || arr.isEmpty()) throw new IOException("Nessun installer da " + url);
        return arr.get(0).path("url").asText();
    }

    // ------------------------------------------------------------------------------------------
    // Start command & JVM args
    // ------------------------------------------------------------------------------------------

    static void writeJvmArgs(WorldRecipe r, Path serverDir) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("-Xms" + r.ramMinMb() + "M");
        lines.add("-Xmx" + r.ramMaxMb() + "M");
        lines.addAll(r.jvmArgs());
        Files.writeString(serverDir.resolve("user_jvm_args.txt"), String.join(System.lineSeparator(), lines) + System.lineSeparator());
    }

    /** Strips interactive {@code pause} commands from Windows server startup scripts (prepareServerPack). */
    public static void sanitizeBatchScripts(Path serverDir) {
        for (String name : List.of("run.bat", "start.bat", "ServerStart.bat", "startserver.bat")) {
            Path bat = serverDir.resolve(name);
            if (!Files.isRegularFile(bat)) continue;
            try {
                String raw = Files.readString(bat, StandardCharsets.UTF_8);
                String patched = raw.replaceAll("(?im)^\\s*pause\\b.*$", "rem pause removed by mc-sync-launcher");
                if (!patched.equals(raw)) {
                    Files.writeString(bat, patched, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** The command that starts this server, or empty if no loader is installed in {@code serverDir}. */
    static List<String> startCommand(WorldRecipe r, Path serverDir) throws IOException {
        List<String> jvm = new ArrayList<>(List.of("java", "-Xms" + r.ramMinMb() + "M", "-Xmx" + r.ramMaxMb() + "M"));
        jvm.addAll(r.jvmArgs());
        ServerProfileDetector.Detection d = ServerProfileDetector.detect(serverDir);
        switch (d.profile()) {
            case FORGE_MODERN -> {
                return d.suggestedCommand();
            }
            case FABRIC -> {
                jvm.addAll(List.of("-jar", "fabric-server-launch.jar", "nogui"));
                return jvm;
            }
            case QUILT -> {
                jvm.addAll(List.of("-jar", "quilt-server-launch.jar", "nogui"));
                return jvm;
            }
            default -> {
                // legacy Forge (< 1.17): forge-<mc>-<ver>.jar next to the installer output
                try (Stream<Path> s = Files.list(serverDir)) {
                    Optional<Path> jar = s.filter(Files::isRegularFile).filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.startsWith("forge-") && n.endsWith(".jar") && !n.contains("installer");
                    }).findFirst();
                    if (jar.isPresent()) {
                        jvm.addAll(List.of("-jar", jar.get().getFileName().toString(), "nogui"));
                        return jvm;
                    }
                }
                return List.of();
            }
        }
    }

    private static int runProcess(List<String> cmd, Path dir, Path javaHome) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        if (javaHome != null) ProcessUtils.applyJavaHome(pb, javaHome);
        Process p = pb.start();
        p.getOutputStream().close();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), ProcessUtils.childCharset()))) {
            String line;
            while ((line = r.readLine()) != null) log.info("[installer] {}", line);
        }
        return p.waitFor();
    }
}
