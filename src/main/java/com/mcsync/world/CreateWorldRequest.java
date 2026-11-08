package com.mcsync.world;

import java.util.List;

/**
 * Everything needed to create a world: the modpack travels inside the request, so the GUI wizard
 * and {@code launch world create ...} share the same structure.
 *
 * @param worldId              slug ({@code stoneblock3}); blank = derived from the display name
 * @param clientPack           mandatory
 * @param serverPack           optional (zip or folder, used once at creation: it is an input, never distributed)
 * @param extraClientOnly      additional client-only globs confirmed by the host (e.g. {@code mods/foo*})
 * @param pinnedConfigGlobs    additional globs of configs that clients must have identical
 * @param serverRamMinMb       0 = default
 */
public record CreateWorldRequest(String worldId, String displayName, ModpackSource clientPack,
                                 ModpackSource serverPack, int ramMinMb, int ramMaxMb, int port,
                                 List<String> whitelist, boolean exportInviteAfterCreate,
                                 List<String> extraClientOnly, List<String> pinnedConfigGlobs) {

    public CreateWorldRequest {
        whitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
        extraClientOnly = extraClientOnly == null ? List.of() : List.copyOf(extraClientOnly);
        pinnedConfigGlobs = pinnedConfigGlobs == null ? List.of() : List.copyOf(pinnedConfigGlobs);
    }

    public CreateWorldRequest(String worldId, String displayName, ModpackSource clientPack, ModpackSource serverPack,
                              int ramMinMb, int ramMaxMb, int port, List<String> whitelist,
                              boolean exportInviteAfterCreate) {
        this(worldId, displayName, clientPack, serverPack, ramMinMb, ramMaxMb, port, whitelist,
                exportInviteAfterCreate, List.of(), List.of());
    }
}
