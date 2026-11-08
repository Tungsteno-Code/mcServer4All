package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Shared server "recipe" stored as {@code worlds/<id>/world.json}: everything every host needs to
 * rebuild the very same server locally.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldRecipe(int schemaVersion, String worldId, String displayName, String minecraft,
                          ModsLock.Loader loader, int javaVersion, List<String> jvmArgs, int port,
                          List<String> whitelist, int ramMinMb, int ramMaxMb, String worldFolderName,
                          String createdBy) {

    public static final int SCHEMA_VERSION = 1;

    public WorldRecipe {
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        whitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
        javaVersion = javaVersion <= 0 ? 21 : javaVersion;
        port = port <= 0 ? 25565 : port;
        ramMinMb = ramMinMb <= 0 ? 2048 : ramMinMb;
        ramMaxMb = ramMaxMb < ramMinMb ? Math.max(ramMinMb, 4096) : ramMaxMb;
        worldFolderName = worldFolderName == null || worldFolderName.isBlank() ? "world" : worldFolderName;
    }

    /** Aligns the recipe with a (possibly updated) lock: minecraft version and loader. */
    public WorldRecipe withPlatform(String mc, ModsLock.Loader l) {
        return new WorldRecipe(schemaVersion, worldId, displayName, mc, l, javaVersion, jvmArgs, port, whitelist,
                ramMinMb, ramMaxMb, worldFolderName, createdBy);
    }
}
