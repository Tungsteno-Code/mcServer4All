package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Robust classifier for modpack / server-pack archives (§6 of the plan).
 *
 * <p>Evaluates together:</p>
 * <ul>
 *     <li>{@code modrinth.index.json} -&gt; {@link Kind#MODRINTH_MRPACK}</li>
 *     <li>{@code manifest.json} with {@code minecraft} and {@code files[]} + {@code overrides/} -&gt; {@link Kind#CURSEFORGE_CLIENT}</li>
 *     <li>JARs in {@code mods/} + server startup scripts / installer jars -&gt; {@link Kind#SERVER_PACK}</li>
 *     <li>When signals conflict or are insufficient -&gt; {@link Kind#AMBIGUOUS} so the wizard can ask for confirmation.</li>
 * </ul>
 */
public final class ZipClassifier {

    public enum Kind {
        MODRINTH_MRPACK,
        CURSEFORGE_CLIENT,
        SERVER_PACK,
        PRISM_INSTANCE,
        FOLDER,
        AMBIGUOUS
    }

    public record Classification(
            Kind kind,
            boolean hasCurseForgeManifest,
            boolean hasModrinthIndex,
            int rootModJars,
            int overrideModJars,
            boolean hasServerScriptOrInstaller,
            String summary) {

        public boolean isAmbiguous() {
            return kind == Kind.AMBIGUOUS;
        }
    }

    private ZipClassifier() {
    }

    public static Classification classify(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IOException("Percorso non trovato: " + path);
        }
        if (Files.isDirectory(path)) {
            if (Files.isRegularFile(path.resolve("mmc-pack.json"))) {
                return new Classification(Kind.PRISM_INSTANCE, false, false, 0, 0, false,
                        "Istanza Prism Launcher (" + path.getFileName() + ")");
            }
            return new Classification(Kind.FOLDER, false, false, 0, 0, false,
                    "Cartella manuale (" + path.getFileName() + ")");
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".mrpack")) {
            return new Classification(Kind.MODRINTH_MRPACK, false, true, 0, 0, false,
                    "Modpack Modrinth (.mrpack)");
        }

        String topPrefix = Zips.singleTopLevelPrefix(path);
        boolean hasCfManifest = false;
        boolean hasMrIndex = false;
        boolean hasOverrides = false;
        int rootModJars = 0;
        int overrideModJars = 0;
        boolean hasServerScript = false;

        try (ZipFile zf = new ZipFile(path.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String stripped = !topPrefix.isEmpty() && name.startsWith(topPrefix)
                        ? name.substring(topPrefix.length()) : name;
                String lower = stripped.toLowerCase(Locale.ROOT);

                if (stripped.equals("modrinth.index.json")) {
                    hasMrIndex = true;
                } else if (stripped.equals("manifest.json")) {
                    try (var in = zf.getInputStream(e)) {
                        JsonNode n = Json.mapper().readTree(in.readNBytes(512 * 1024));
                        if (n.has("minecraft") && n.path("files").isArray()) {
                            hasCfManifest = true;
                        }
                    } catch (Exception ignored) {
                    }
                } else if (lower.startsWith("overrides/")) {
                    hasOverrides = true;
                    if (lower.startsWith("overrides/mods/") && lower.endsWith(".jar")) {
                        overrideModJars++;
                    }
                } else if (lower.startsWith("mods/") && lower.endsWith(".jar")) {
                    rootModJars++;
                } else if (isServerScriptOrInstaller(lower)) {
                    hasServerScript = true;
                }
            }
        }

        if (hasMrIndex) {
            return new Classification(Kind.MODRINTH_MRPACK, hasCfManifest, true, rootModJars, overrideModJars,
                    hasServerScript, "Modpack Modrinth (modrinth.index.json)");
        }
        if (hasCfManifest && !hasServerScript && rootModJars == 0) {
            return new Classification(Kind.CURSEFORGE_CLIENT, true, false, rootModJars, overrideModJars,
                    false, "Modpack client CurseForge (manifest.json" + (hasOverrides ? " + overrides/" : "") + ")");
        }
        if (!hasCfManifest && (hasServerScript || rootModJars > 0)) {
            return new Classification(Kind.SERVER_PACK, false, false, rootModJars, overrideModJars,
                    hasServerScript, "Server Pack (" + rootModJars + " jar in mods/"
                    + (hasServerScript ? ", script server rilevato" : "") + ")");
        }
        return new Classification(Kind.AMBIGUOUS, hasCfManifest, false, rootModJars, overrideModJars,
                hasServerScript, "Archivio ambiguo (manifest=" + hasCfManifest + ", jar mods/=" + rootModJars
                + ", script server=" + hasServerScript + ")");
    }

    /** Converts a path into the appropriate {@link ModpackSource} using {@link #classify(Path)}. */
    public static ModpackSource toModpackSource(Path path) throws IOException {
        Classification c = classify(path);
        return switch (c.kind()) {
            case MODRINTH_MRPACK -> new ModpackSource.ModrinthMrpack(path);
            case CURSEFORGE_CLIENT -> new ModpackSource.CurseForgeZip(path);
            case PRISM_INSTANCE -> new ModpackSource.PrismInstance(path);
            case FOLDER -> new ModpackSource.Folder(path);
            case SERVER_PACK, AMBIGUOUS -> new ModpackSource.CurseForgeZip(path);
        };
    }

    private static boolean isServerScriptOrInstaller(String lowerRelPath) {
        if (lowerRelPath.indexOf('/') >= 0) return false;
        if (lowerRelPath.endsWith(".jar") && (lowerRelPath.contains("installer")
                || lowerRelPath.startsWith("forge-") || lowerRelPath.startsWith("neoforge-")
                || lowerRelPath.startsWith("fabric-server") || lowerRelPath.startsWith("quilt-server"))) {
            return true;
        }
        return lowerRelPath.equals("run.bat") || lowerRelPath.equals("run.sh")
                || lowerRelPath.equals("start.bat") || lowerRelPath.equals("start.sh")
                || lowerRelPath.equals("startserver.bat") || lowerRelPath.equals("startserver.sh")
                || lowerRelPath.equals("serverstart.bat") || lowerRelPath.equals("serverstart.sh")
                || lowerRelPath.equals("user_jvm_args.txt");
    }
}
