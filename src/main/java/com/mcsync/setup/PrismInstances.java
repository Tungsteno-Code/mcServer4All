package com.mcsync.setup;

import com.mcsync.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Discovers Prism Launcher installations and their instances (portable and system-wide). */
public final class PrismInstances {

    /**
     * @param id      folder name, used by {@code prismlauncher --launch <id>}
     * @param name    display name from {@code instance.cfg}
     * @param gameDir {@code minecraft/} (Prism) or {@code .minecraft/} folder with mods/ and config/
     */
    public record Instance(String id, String name, Path gameDir) {
        @Override
        public String toString() {
            return name.equals(id) ? name : name + "  [" + id + "]";
        }
    }

    private PrismInstances() {
    }

    /** Candidate Prism Launcher data/installation folders, most specific first. */
    public static List<Path> launcherDirs(Path baseDir) {
        Set<Path> dirs = new LinkedHashSet<>();
        if (baseDir != null) dirs.add(baseDir.resolve(".tools").resolve("PrismLauncher"));
        String home = System.getProperty("user.home");
        if (FileUtils.IS_WINDOWS) {
            String appData = System.getenv("APPDATA");
            String local = System.getenv("LOCALAPPDATA");
            if (appData != null) dirs.add(Path.of(appData, "PrismLauncher"));
            if (local != null) dirs.add(Path.of(local, "Programs", "PrismLauncher"));
        } else {
            dirs.add(Path.of(home, ".local", "share", "PrismLauncher"));
            dirs.add(Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"));
            dirs.add(Path.of(home, "Library", "Application Support", "PrismLauncher"));
        }
        return dirs.stream().filter(Files::isDirectory).toList();
    }

    public static List<Instance> scan(Path baseDir) {
        List<Instance> out = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path dir : launcherDirs(baseDir)) {
            Path instances = dir.resolve("instances");
            if (!Files.isDirectory(instances) || !seen.add(instances.toAbsolutePath().normalize())) continue;
            try (Stream<Path> s = Files.list(instances)) {
                for (Path inst : s.filter(Files::isDirectory).sorted().toList()) {
                    String id = inst.getFileName().toString();
                    if (id.startsWith(".") || id.startsWith("_")) continue;
                    Path game = Files.isDirectory(inst.resolve("minecraft")) ? inst.resolve("minecraft")
                            : Files.isDirectory(inst.resolve(".minecraft")) ? inst.resolve(".minecraft") : null;
                    if (game == null) continue;
                    out.add(new Instance(id, readName(inst, id), game));
                }
            } catch (IOException ignored) {
                // unreadable folder
            }
        }
        return out;
    }

    private static String readName(Path inst, String def) {
        Path cfg = inst.resolve("instance.cfg");
        if (!Files.isRegularFile(cfg)) return def;
        try (Stream<String> lines = Files.lines(cfg, StandardCharsets.UTF_8)) {
            return lines.filter(l -> l.startsWith("name=")).map(l -> l.substring(5).trim())
                    .filter(n -> !n.isEmpty()).findFirst().orElse(def);
        } catch (Exception e) {
            return def;
        }
    }

    /** Display name of the instance a game directory belongs to (for the dashboard). */
    public static String instanceName(Path gameDir) {
        if (gameDir == null) return null;
        Path inst = gameDir.getParent();
        if (inst == null) return null;
        return readName(inst, inst.getFileName().toString());
    }

    /** Canonical Prism instance folder name for a world: {@code mcsync-<worldId>}. */
    public static String worldInstanceId(String worldId) {
        return com.mcsync.world.PrismInstanceWriter.instanceId(worldId);
    }

    /** Creates or updates the Prism instance {@code mcsync-<worldId>} from the locked modpack metadata. */
    public static Path ensureWorldInstance(
            Path baseDir, String worldId, String displayName, String minecraft,
            com.mcsync.model.ModsLock.Loader loader, int ramMinMb, int ramMaxMb) throws IOException {
        Path prismData = com.mcsync.world.PrismInstanceWriter.locatePrismData(baseDir);
        return new com.mcsync.world.PrismInstanceWriter(prismData)
                .ensure(worldId, displayName, minecraft, loader, ramMinMb, ramMaxMb);
    }
}
