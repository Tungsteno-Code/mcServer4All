package com.mcsync.setup;

import com.mcsync.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Recognises the type of Minecraft server installed in {@code serverDirectory} and validates /
 * repairs the configured start command before boot.
 *
 * <ul>
 *     <li><b>FORGE_MODERN</b>: {@code run.bat}/{@code run.sh} + {@code user_jvm_args.txt} + {@code libraries/}</li>
 *     <li><b>FABRIC</b> / <b>QUILT</b>: {@code fabric-server-launch.jar} / {@code quilt-server-launch.jar}</li>
 *     <li><b>JAR</b>: a generic runnable {@code server.jar}</li>
 *     <li><b>INSTALLER_PENDING</b>: a CurseForge / FTB / Forge server pack whose installer was never run</li>
 * </ul>
 */
public final class ServerProfileDetector {

    private static final Logger log = LoggerFactory.getLogger(ServerProfileDetector.class);

    public enum Profile {
        FORGE_MODERN("Forge / NeoForge (run script)"),
        FABRIC("Fabric"),
        QUILT("Quilt"),
        JAR("Server JAR generico"),
        INSTALLER_PENDING("Server Pack da installare"),
        UNKNOWN("Sconosciuto");

        public final String label;

        Profile(String label) {
            this.label = label;
        }
    }

    public record Detection(Profile profile, List<String> suggestedCommand, Path installer) {
    }

    private ServerProfileDetector() {
    }

    public static String runScriptName() {
        return FileUtils.IS_WINDOWS ? "run.bat" : "run.sh";
    }

    public static Detection detect(Path serverDir) {
        if (serverDir == null || !Files.isDirectory(serverDir)) return new Detection(Profile.UNKNOWN, List.of(), null);
        Path run = serverDir.resolve(runScriptName());
        boolean libraries = Files.isDirectory(serverDir.resolve("libraries"));
        if (Files.isRegularFile(run) && (libraries || Files.isRegularFile(serverDir.resolve("user_jvm_args.txt")))) {
            return new Detection(Profile.FORGE_MODERN, List.of(runScriptName(), "nogui"), null);
        }
        if (Files.isRegularFile(serverDir.resolve("fabric-server-launch.jar"))) {
            return new Detection(Profile.FABRIC, jarCommand("fabric-server-launch.jar"), null);
        }
        if (Files.isRegularFile(serverDir.resolve("quilt-server-launch.jar"))) {
            return new Detection(Profile.QUILT, jarCommand("quilt-server-launch.jar"), null);
        }
        Optional<Path> installer = findInstaller(serverDir);
        if (installer.isPresent() && !libraries) {
            return new Detection(Profile.INSTALLER_PENDING, List.of(), installer.get());
        }
        if (Files.isRegularFile(run)) {
            return new Detection(Profile.FORGE_MODERN, List.of(runScriptName(), "nogui"), null);
        }
        if (Files.isRegularFile(serverDir.resolve("server.jar"))) {
            return new Detection(Profile.JAR, jarCommand("server.jar"), null);
        }
        return new Detection(Profile.UNKNOWN, List.of(), null);
    }

    private static List<String> jarCommand(String jar) {
        return List.of("java", "-Xms4G", "-Xmx6G", "-XX:+UseG1GC", "-jar", jar, "nogui");
    }

    /** Forge/NeoForge installer jars or CurseForge/FTB install scripts that were not run yet. */
    static Optional<Path> findInstaller(Path serverDir) {
        try (Stream<Path> s = Files.list(serverDir)) {
            List<Path> files = s.filter(Files::isRegularFile).toList();
            for (Path p : files) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jar") && n.contains("installer")) return Optional.of(p);
            }
            for (Path p : files) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean script = FileUtils.IS_WINDOWS ? n.endsWith(".bat") || n.endsWith(".cmd") : n.endsWith(".sh");
                if (script && (n.startsWith("install") || n.equals("startserver.bat") || n.equals("startserver.sh")
                        || n.contains("serverinstall"))) {
                    return Optional.of(p);
                }
            }
        } catch (IOException ignored) {
            // not readable
        }
        return Optional.empty();
    }

    /**
     * Command needed to run a pending installer (Forge installer jar or pack script).
     */
    public static List<String> installerCommand(Path installer, String javaBin) {
        String n = installer.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".jar")) return List.of(javaBin, "-jar", installer.getFileName().toString(), "--installServer");
        if (n.endsWith(".sh")) return List.of("bash", installer.getFileName().toString());
        return List.of("cmd.exe", "/c", installer.getFileName().toString());
    }

    /**
     * Pre-flight validation of the server start command. Repairs the common mistakes:
     * <ul>
     *     <li>{@code java -jar run.bat} → {@code run.bat nogui} ("Invalid or corrupt jarfile run.bat")</li>
     *     <li>{@code -jar <missing.jar>} while a run script exists → the run script</li>
     *     <li>script that does not exist while the platform run script exists → the run script</li>
     * </ul>
     *
     * @return the repaired command (or the original one when it already looks valid)
     */
    public static List<String> repairCommand(List<String> cmd, Path serverDir) {
        if (cmd == null || cmd.isEmpty()) {
            Detection d = detect(serverDir);
            return d.suggestedCommand();
        }
        List<String> out = new ArrayList<>(cmd);
        String first = out.get(0);
        String firstName = Path.of(stripQuotes(first)).getFileName() == null ? first
                : Path.of(stripQuotes(first)).getFileName().toString().toLowerCase(Locale.ROOT);
        boolean isJava = firstName.equals("java") || firstName.equals("java.exe") || firstName.equals("javaw.exe");
        Path run = serverDir.resolve(runScriptName());

        if (isJava) {
            // A script passed to java ( -jar run.bat / java run.sh )
            for (String a : out.subList(1, out.size())) {
                String l = a.toLowerCase(Locale.ROOT);
                if (l.endsWith(".bat") || l.endsWith(".cmd") || l.endsWith(".sh")) {
                    String script = Files.isRegularFile(serverDir.resolve(a)) ? a
                            : Files.isRegularFile(run) ? runScriptName() : a;
                    log.warn("serverStartCommand passes the script '{}' to java (\"Invalid or corrupt jarfile\"): "
                            + "using [{} nogui] instead", a, script);
                    return List.of(script, "nogui");
                }
            }
            int jarIdx = out.indexOf("-jar");
            if (jarIdx >= 0 && jarIdx + 1 < out.size()) {
                Path jar = serverDir.resolve(stripQuotes(out.get(jarIdx + 1)));
                if (!Files.isRegularFile(jar)) {
                    Detection d = detect(serverDir);
                    if (!d.suggestedCommand().isEmpty()) {
                        log.warn("Server jar '{}' not found: detected {} server, using {}", out.get(jarIdx + 1),
                                d.profile().label, d.suggestedCommand());
                        return d.suggestedCommand();
                    }
                }
            }
            return out;
        }

        String l = firstName;
        if (l.endsWith(".bat") || l.endsWith(".cmd") || l.endsWith(".sh")) {
            Path script = serverDir.resolve(stripQuotes(first));
            if (!Files.isRegularFile(script) && Files.isRegularFile(run)) {
                log.warn("Start script '{}' not found: using {}", first, runScriptName());
                out.set(0, runScriptName());
            } else if (!FileUtils.IS_WINDOWS && (l.endsWith(".bat") || l.endsWith(".cmd")) && Files.isRegularFile(run)) {
                out.set(0, runScriptName());
            } else if (FileUtils.IS_WINDOWS && l.endsWith(".sh") && Files.isRegularFile(run)) {
                out.set(0, runScriptName());
            }
            if (out.size() == 1) out.add("nogui");
        }
        return out;
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
        return t;
    }

    /**
     * Infers Minecraft version and mod loader from an existing server folder and/or Prism instance
     * and constructs a {@link com.mcsync.model.WorldRecipe} ({@code world.json}).
     */
    public static com.mcsync.model.WorldRecipe initWorldRecipe(
            Path serverDir, Path clientGameDir, String worldId, String displayName,
            com.mcsync.config.Config cfg) {
        String mc = "1.20.1";
        com.mcsync.model.ModsLock.Loader loader = new com.mcsync.model.ModsLock.Loader("forge", "47.3.0");

        // 1. Try Prism instance mmc-pack.json if available
        if (clientGameDir != null && clientGameDir.getParent() != null) {
            Path mmc = clientGameDir.getParent().resolve("mmc-pack.json");
            if (Files.isRegularFile(mmc)) {
                try {
                    com.fasterxml.jackson.databind.JsonNode root = com.mcsync.util.Json.mapper().readTree(mmc.toFile());
                    for (com.fasterxml.jackson.databind.JsonNode c : root.path("components")) {
                        String uid = c.path("uid").asText("");
                        String ver = c.path("version").asText(c.path("cachedVersion").asText(""));
                        if (ver.isEmpty()) continue;
                        switch (uid) {
                            case "net.minecraft" -> mc = ver;
                            case "net.minecraftforge" -> loader = new com.mcsync.model.ModsLock.Loader("forge", ver);
                            case "net.neoforged" -> loader = new com.mcsync.model.ModsLock.Loader("neoforge", ver);
                            case "net.fabricmc.fabric-loader" -> loader = new com.mcsync.model.ModsLock.Loader("fabric", ver);
                            case "org.quiltmc.quilt-loader" -> loader = new com.mcsync.model.ModsLock.Loader("quilt", ver);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        // 2. Inspect serverDir libraries / jars if still default or unknown
        if (serverDir != null && Files.isDirectory(serverDir)) {
            Path forgeLib = serverDir.resolve("libraries/net/minecraftforge/forge");
            if (Files.isDirectory(forgeLib)) {
                try (Stream<Path> s = Files.list(forgeLib)) {
                    Optional<String> dir = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).findFirst();
                    if (dir.isPresent() && dir.get().contains("-")) {
                        int dash = dir.get().indexOf('-');
                        mc = dir.get().substring(0, dash);
                        loader = new com.mcsync.model.ModsLock.Loader("forge", dir.get().substring(dash + 1));
                    }
                } catch (IOException ignored) {
                }
            } else if (Files.isDirectory(serverDir.resolve("libraries/net/neoforged/neoforge"))) {
                try (Stream<Path> s = Files.list(serverDir.resolve("libraries/net/neoforged/neoforge"))) {
                    Optional<String> dir = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).findFirst();
                    if (dir.isPresent()) {
                        loader = new com.mcsync.model.ModsLock.Loader("neoforge", dir.get());
                    }
                } catch (IOException ignored) {
                }
            } else if (Files.isRegularFile(serverDir.resolve("fabric-server-launch.jar"))) {
                loader = new com.mcsync.model.ModsLock.Loader("fabric", loader.version());
            } else if (Files.isRegularFile(serverDir.resolve("quilt-server-launch.jar"))) {
                loader = new com.mcsync.model.ModsLock.Loader("quilt", loader.version());
            }
        }

        int[] ramGb = com.mcsync.diagnostics.ConfigFixer.readRam(cfg);
        return new com.mcsync.model.WorldRecipe(
                com.mcsync.model.WorldRecipe.SCHEMA_VERSION,
                worldId,
                displayName != null && !displayName.isBlank() ? displayName : worldId,
                mc,
                loader,
                21,
                List.of(),
                cfg.serverPort(),
                cfg.whitelistPlayers(),
                ramGb[0] * 1024,
                ramGb[1] * 1024,
                cfg.worldFolderName(),
                cfg.playerId());
    }
}
