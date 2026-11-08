package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content of {@code manifest.json}: describes the latest committed world state and the
 * **session-only delta** ({@code deltaKey}) so that only files modified in the last session are
 * uploaded/downloaded over Git.
 *
 * <p>The manifest is always written <em>last</em>, so a crash during upload never exposes a
 * partial archive.</p>
 *
 * @param baseVersion   version from which {@code deltaKey} was computed (usually {@code version - 1})
 * @param deltaKey      archive containing ONLY the files created/modified in the last session
 * @param deltaSha256   SHA-256 of {@code deltaKey}
 * @param deltaSizeBytes size in bytes of {@code deltaKey}
 * @param deletedFiles  relative paths (forward slashes) deleted during the last session
 * @param worldFiles    complete index {@code relativePath -> sha256} of the world at {@code version}
 *                      so the next host can compute its session delta accurately
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldManifest(
        int schemaVersion,
        long version,
        long lastUpdated,
        String sha256,
        long sizeBytes,
        String latestKey,
        String backupKey,
        String uploadedBy,
        String sessionId,
        boolean cleanShutdown,
        String worldFolderName,
        Long baseVersion,
        String deltaKey,
        String deltaSha256,
        Long deltaSizeBytes,
        List<String> deletedFiles,
        Map<String, String> worldFiles) {

    public static final int SCHEMA_VERSION = 1;

    public WorldManifest {
        deletedFiles = deletedFiles == null ? List.of() : List.copyOf(deletedFiles);
        worldFiles = worldFiles == null ? new TreeMap<>() : new TreeMap<>(worldFiles);
    }

    /** Backwards-compatible constructor for full-snapshot manifests. */
    public WorldManifest(
            int schemaVersion,
            long version,
            long lastUpdated,
            String sha256,
            long sizeBytes,
            String latestKey,
            String backupKey,
            String uploadedBy,
            String sessionId,
            boolean cleanShutdown,
            String worldFolderName) {
        this(schemaVersion, version, lastUpdated, sha256, sizeBytes, latestKey, backupKey,
                uploadedBy, sessionId, cleanShutdown, worldFolderName, null, null, null, null, List.of(), Map.of());
    }
}
