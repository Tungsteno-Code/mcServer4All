package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content of {@code mods_manifest.json}: relative path (forward slashes) to SHA-256.
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "lastUpdated": 1700000000,
 *   "files": { "mods/create-1.20.1-0.5.1.f.jar": "e3b0c4..." }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModsManifest(int schemaVersion, long lastUpdated, String publishedBy, Map<String, String> files) {

    public static final int SCHEMA_VERSION = 1;

    public ModsManifest {
        files = files == null ? new TreeMap<>() : new TreeMap<>(files);
    }

    /** Stable fingerprint of the file set (ignores timestamps). */
    @JsonIgnore
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : files.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
