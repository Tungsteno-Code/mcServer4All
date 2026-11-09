package com.mcsync.world;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcsync.model.ModsLock;
import com.mcsync.setup.PrismInstances;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates / updates the Prism instance {@code mcsync-<worldId>} of a world straight from the lock
 * (Minecraft version + loader): {@code instance.cfg} + {@code mmc-pack.json}, no zip import needed.
 * Prism fills in the remaining required components (LWJGL, mappings) when it first opens the instance.
 */
public final class PrismInstanceWriter {

    private static final Logger log = LoggerFactory.getLogger(PrismInstanceWriter.class);

    private final Path prismDataDir;

    public PrismInstanceWriter(Path prismDataDir) {
        this.prismDataDir = prismDataDir;
    }

    /** First existing Prism data folder, or the OS default location. */
    public static Path locatePrismData(Path baseDir) {
        List<Path> dirs = PrismInstances.launcherDirs(baseDir);
        for (Path d : dirs) {
            if (Files.isDirectory(d.resolve("instances"))) return d;
        }
        if (!dirs.isEmpty()) return dirs.get(0);
        String home = System.getProperty("user.home");
        if (FileUtils.IS_WINDOWS && System.getenv("APPDATA") != null) return Path.of(System.getenv("APPDATA"), "PrismLauncher");
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return Path.of(home, "Library", "Application Support", "PrismLauncher");
        }
        return Path.of(home, ".local", "share", "PrismLauncher");
    }

    public static String instanceId(String worldId) {
        return "mcsync-" + worldId;
    }

    /** @return the game directory ({@code <instance>/minecraft}) */
    public Path ensure(String worldId, String displayName, String minecraft, ModsLock.Loader loader,
                       int ramMinMb, int ramMaxMb) throws IOException {
        Path inst = prismDataDir.resolve("instances").resolve(instanceId(worldId));
        Path game = inst.resolve("minecraft");
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("config"));

        int effectiveMaxMb = Math.max(ramMaxMb, 8192);
        int effectiveMinMb = Math.min(Math.max(ramMinMb, 4096), effectiveMaxMb);

        Path cfg = inst.resolve("instance.cfg");
        if (!Files.isRegularFile(cfg)) {
            String text = "[General]\nConfigVersion=1.2\nInstanceType=OneSix\niconKey=default\n"
                    + "name=" + displayName.replace('\n', ' ') + "\n"
                    + "OverrideMemory=true\nMinMemAlloc=" + effectiveMinMb + "\nMaxMemAlloc=" + effectiveMaxMb + "\n"
                    + "OverrideCommands=false\n";
            Files.writeString(cfg, text, StandardCharsets.UTF_8);
        } else {
            upgradeInstanceMemoryIfNeeded(cfg, effectiveMinMb, effectiveMaxMb);
        }
        Path pack = inst.resolve("mmc-pack.json");
        byte[] wanted = Json.toBytes(components(minecraft, loader));
        if (!Files.isRegularFile(pack) || !sameComponents(pack, wanted)) {
            Files.write(pack, wanted);
            log.info("Istanza Prism {} aggiornata: Minecraft {} + {} {}", instanceId(worldId), minecraft, loader.type(),
                    loader.version());
        }
        return game;
    }

    static ObjectNode components(String mc, ModsLock.Loader loader) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("formatVersion", 1);
        ArrayNode comps = root.putArray("components");
        comps.addObject().put("uid", "net.minecraft").put("version", mc).put("important", true);
        switch (loader.type()) {
            case "forge" -> comps.addObject().put("uid", "net.minecraftforge").put("version", loader.version());
            case "neoforge" -> comps.addObject().put("uid", "net.neoforged").put("version", loader.version());
            case "fabric" -> {
                comps.addObject().put("uid", "net.fabricmc.intermediary").put("version", mc);
                comps.addObject().put("uid", "net.fabricmc.fabric-loader").put("version", loader.version());
            }
            case "quilt" -> {
                comps.addObject().put("uid", "net.fabricmc.intermediary").put("version", mc);
                comps.addObject().put("uid", "org.quiltmc.quilt-loader").put("version", loader.version());
            }
            default -> throw new IllegalArgumentException("Loader non supportato: " + loader.type());
        }
        return root;
    }

    private static void upgradeInstanceMemoryIfNeeded(Path cfg, int minMb, int maxMb) {
        try {
            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(cfg, StandardCharsets.UTF_8));
            boolean changed = false;
            boolean hasMax = false;
            boolean hasOverride = false;
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
                        if (cur < maxMb) {
                            lines.set(i, "MaxMemAlloc=" + maxMb);
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
                lines.add("MinMemAlloc=" + minMb);
                lines.add("MaxMemAlloc=" + maxMb);
                changed = true;
            }
            if (changed) {
                Files.write(cfg, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean sameComponents(Path pack, byte[] wanted) {
        try {
            var have = PackReader.prismMeta(pack);
            var want = PackReader.prismMetaFromBytes(wanted);
            return java.util.Objects.equals(have, want);
        } catch (IOException e) {
            return false;
        }
    }

    /** Removes the instance folder (used when a world is deleted locally). */
    public void delete(String worldId) throws IOException {
        Path inst = prismDataDir.resolve("instances").resolve(instanceId(worldId));
        if (Files.isDirectory(inst)) FileUtils.deleteRecursively(inst);
    }

    /** Client launch command for the world instance. */
    public static List<String> launchCommand(Path prismExe, String worldId) {
        String exe = prismExe != null ? prismExe.toString() : "prismlauncher";
        if (prismExe == null && Files.isDirectory(Path.of(System.getProperty("user.home"), ".var", "app", "org.prismlauncher.PrismLauncher"))) {
            return List.of("flatpak", "run", "org.prismlauncher.PrismLauncher", "--launch", instanceId(worldId),
                    "--server", "{serverAddress}");
        }
        return List.of(exe, "--launch", instanceId(worldId), "--server", "{serverAddress}");
    }
}
