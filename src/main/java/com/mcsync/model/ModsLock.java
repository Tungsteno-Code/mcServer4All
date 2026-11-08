package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The locked modpack of a world ({@code worlds/<id>/mods.lock.json}): the single source of truth.
 * Host and clients materialise {@code mods/} starting from here, never from a scan.
 *
 * @param files          relative path ({@code mods/x.jar}) -> entry
 * @param pinnedConfigs  config path -> sha256 that clients must have identical (content in blob store)
 * @param serverFiles    non-jar server files (kubejs, defaultconfigs, datapacks...) path -> sha256
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModsLock(int schemaVersion, long modpackVersion, String minecraft, Loader loader,
                       Map<String, Entry> files, Map<String, String> pinnedConfigs,
                       Map<String, String> serverFiles, Map<String, String> overridesBundles,
                       String updatedBy, long updatedAt) {

    public static final int SCHEMA_VERSION = 2;

    public static final String SIDE_BOTH = "both";
    public static final String SIDE_SERVER = "server";
    public static final String SIDE_CLIENT = "client";

    public static final String SRC_MODRINTH = "modrinth";
    public static final String SRC_CURSEFORGE = "curseforge";
    public static final String SRC_BLOB = "blob";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Loader(String type, String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String type, String id, String url) {
        public static Source blob() {
            return new Source(SRC_BLOB, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String sha256, long size, String side, Source source) {
        public Entry {
            side = side == null || side.isBlank() ? SIDE_BOTH : side;
            source = source == null ? Source.blob() : source;
        }

        public boolean onServer() {
            return !SIDE_CLIENT.equals(side);
        }

        public boolean onClient() {
            return !SIDE_SERVER.equals(side);
        }
    }

    public ModsLock {
        files = files == null ? new TreeMap<>() : new TreeMap<>(files);
        pinnedConfigs = pinnedConfigs == null ? new TreeMap<>() : new TreeMap<>(pinnedConfigs);
        serverFiles = serverFiles == null ? new TreeMap<>() : new TreeMap<>(serverFiles);
        overridesBundles = overridesBundles == null ? new TreeMap<>() : new TreeMap<>(overridesBundles);
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
    }

    /** Backwards-compatible v1 constructor. */
    public ModsLock(int schemaVersion, long modpackVersion, String minecraft, Loader loader,
                    Map<String, Entry> files, Map<String, String> pinnedConfigs,
                    Map<String, String> serverFiles, String updatedBy, long updatedAt) {
        this(schemaVersion, modpackVersion, minecraft, loader, files, pinnedConfigs, serverFiles, Map.of(), updatedBy, updatedAt);
    }

    public ModsLock withVersion(long version, String by, long at) {
        return new ModsLock(schemaVersion, version, minecraft, loader, files, pinnedConfigs, serverFiles, overridesBundles, by, at);
    }

    /**
     * Hash of what matters (platform, jars, pinned configs, server files, overrides bundles, version) ignoring who/when:
     * compared against {@code LocalState} to skip all work when nothing changed.
     */
    @JsonIgnore
    public String contentHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            add(md, "mc=" + minecraft);
            add(md, "loader=" + (loader == null ? "" : loader.type() + ":" + loader.version()));
            add(md, "v=" + modpackVersion);
            files.forEach((k, e) -> add(md, "f:" + k + ":" + e.sha256() + ":" + e.side()));
            pinnedConfigs.forEach((k, h) -> add(md, "p:" + k + ":" + h));
            serverFiles.forEach((k, h) -> add(md, "s:" + k + ":" + h));
            overridesBundles.forEach((k, h) -> add(md, "o:" + k + ":" + h));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
    }
}
