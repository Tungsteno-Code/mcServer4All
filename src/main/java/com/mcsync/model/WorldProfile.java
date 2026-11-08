package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A world known to this PC (entry of {@code worlds[]} in {@code config.json}).
 *
 * <p>Only machine-local facts live here; the shared "recipe" is {@link WorldRecipe} in the repo.</p>
 *
 * @param id                  slug, also the repo folder ({@code worlds/<id>/}) and Prism instance suffix
 * @param displayName         human readable name shown in the world list
 * @param serverDirectory     local server folder of this world
 * @param prismInstanceId     Prism instance folder name ({@code mcsync-<id>}), blank until created
 * @param clientInstanceDirectory game directory ({@code minecraft/}) of the Prism instance
 * @param role                {@code CREATOR} or {@code MEMBER}
 * @param storage             repo/bucket of this world when it differs from the global one (null = global)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldProfile(String id, String displayName, String serverDirectory, String prismInstanceId,
                           String clientInstanceDirectory, String role, Invite.Storage storage) {

    public WorldProfile {
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        serverDirectory = serverDirectory == null ? "" : serverDirectory;
        prismInstanceId = prismInstanceId == null ? "" : prismInstanceId;
        clientInstanceDirectory = clientInstanceDirectory == null ? "" : clientInstanceDirectory;
        role = role == null || role.isBlank() ? "MEMBER" : role;
    }

    public WorldProfile(String id, String displayName, String serverDirectory, String prismInstanceId,
                        String clientInstanceDirectory, String role) {
        this(id, displayName, serverDirectory, prismInstanceId, clientInstanceDirectory, role, null);
    }

    public WorldProfile withPrism(String instanceId, String gameDir) {
        return new WorldProfile(id, displayName, serverDirectory, instanceId, gameDir, role, storage);
    }

    public WorldProfile withServerDirectory(String dir) {
        return new WorldProfile(id, displayName, dir, prismInstanceId, clientInstanceDirectory, role, storage);
    }

    /** Valid world ids: lowercase slug, 1..48 chars. */
    public static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,47}");
    }

    /** Turns any text into a valid world id. */
    public static String slugify(String text) {
        String s = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 48) s = s.substring(0, 48);
        return s.isEmpty() ? "world" : s;
    }
}
