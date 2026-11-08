package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.model.ModsLock;
import com.mcsync.util.Glob;
import com.mcsync.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides whether a jar is client-only, server-only or both: loader metadata first
 * ({@code fabric.mod.json "environment"}, {@code quilt.mod.json}, Forge {@code mods.toml}
 * {@code side="CLIENT"}), then the default client-only name list.
 */
public final class ModSideDetector {

    /** Mods that crash a dedicated server or only make sense on the client. */
    public static final List<String> DEFAULT_CLIENT_ONLY = List.of(
            "mods/sodium*", "mods/iris*", "mods/oculus*", "mods/embeddium*", "mods/rubidium*", "mods/optifine*",
            "mods/optifabric*", "mods/entityculling*", "mods/dynamic-fps*", "mods/dynamicfps*", "mods/modmenu*",
            "mods/reeses-sodium*", "mods/sodium-extra*", "mods/xaero*", "mods/replaymod*",
            "mods/ImmediatelyFast*", "mods/immediatelyfast*", "mods/continuity*", "mods/lambdynamiclights*",
            "mods/BetterF3*", "mods/betterf3*", "mods/shulkerboxtooltip*", "mods/Controlling*", "mods/controlling*",
            "mods/Searchables*", "mods/searchables*", "mods/legendarytooltips*", "mods/ItemBorders*");

    private static final Pattern TOML_SIDE = Pattern.compile("side\\s*=\\s*[\"']?(CLIENT|SERVER|BOTH)[\"']?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOML_DEP_BLOCK = Pattern.compile("\\[\\[dependencies\\.[^\\]]+\\]\\]");

    private final Glob clientOnly;

    public ModSideDetector(List<String> extraClientOnlyGlobs) {
        List<String> all = new java.util.ArrayList<>(DEFAULT_CLIENT_ONLY);
        if (extraClientOnlyGlobs != null) all.addAll(extraClientOnlyGlobs);
        this.clientOnly = new Glob(all);
    }

    /** @return one of {@link ModsLock#SIDE_BOTH}, {@link ModsLock#SIDE_CLIENT}, {@link ModsLock#SIDE_SERVER} */
    public String detect(String relPath, Path jar) {
        String meta = fromMetadata(jar);
        if (meta != null) return meta;
        return clientOnly.matches(relPath) ? ModsLock.SIDE_CLIENT : ModsLock.SIDE_BOTH;
    }

    static String fromMetadata(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fabric = z.getEntry("fabric.mod.json");
            if (fabric != null) {
                JsonNode n = Json.mapper().readTree(read(z, fabric));
                String env = n.path("environment").asText("*").toLowerCase(Locale.ROOT);
                if (env.equals("client")) return ModsLock.SIDE_CLIENT;
                if (env.equals("server")) return ModsLock.SIDE_SERVER;
                return null;
            }
            ZipEntry quilt = z.getEntry("quilt.mod.json");
            if (quilt != null) {
                JsonNode n = Json.mapper().readTree(read(z, quilt));
                String env = n.path("minecraft").path("environment").asText("*").toLowerCase(Locale.ROOT);
                if (env.equals("client")) return ModsLock.SIDE_CLIENT;
                if (env.equals("dedicated_server")) return ModsLock.SIDE_SERVER;
                return null;
            }
            ZipEntry toml = z.getEntry("META-INF/mods.toml");
            if (toml == null) toml = z.getEntry("META-INF/neoforge.mods.toml");
            if (toml != null) return forgeSide(new String(read(z, toml), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // unreadable jar: fall back to name based detection
        }
        return null;
    }

    /** Client-only if every dependency block declaring a {@code side} says CLIENT for minecraft/forge. */
    static String forgeSide(String toml) {
        Matcher blocks = TOML_DEP_BLOCK.matcher(toml);
        boolean sawMinecraftClient = false;
        while (blocks.find()) {
            int end = toml.indexOf("[[", blocks.end());
            String block = toml.substring(blocks.end(), end < 0 ? toml.length() : end);
            if (!Pattern.compile("modId\\s*=\\s*[\"'](minecraft|forge|neoforge)[\"']").matcher(block).find()) continue;
            Matcher s = TOML_SIDE.matcher(block);
            if (s.find() && s.group(1).equalsIgnoreCase("CLIENT")) sawMinecraftClient = true;
        }
        return sawMinecraftClient ? ModsLock.SIDE_CLIENT : null;
    }

    private static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return in.readNBytes(2 * 1024 * 1024);
        }
    }
}
