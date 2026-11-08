package com.mcsync.world;

import com.mcsync.model.ModsLock;
import com.mcsync.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A modpack unpacked into a folder with {@code mods/}, {@code config/} and the other overrides,
 * plus what the pack metadata already tells us.
 *
 * @param root          folder with the pack content
 * @param knownSources  relative path -&gt; source already known from the pack metadata (Modrinth/CurseForge)
 * @param knownSides    relative path -&gt; side from the pack metadata (e.g. Modrinth {@code env})
 * @param serverOverrides optional folder with server-only extra files (Modrinth {@code server-overrides/})
 * @param temporary     delete {@code root} on {@link #close()}
 */
public record PackStage(String minecraft, ModsLock.Loader loader, Path root,
                        Map<String, ModsLock.Source> knownSources, Map<String, String> knownSides,
                        Path serverOverrides, boolean temporary) implements AutoCloseable {

    public PackStage {
        knownSources = knownSources == null ? Map.of() : Map.copyOf(knownSources);
        knownSides = knownSides == null ? Map.of() : Map.copyOf(knownSides);
    }

    @Override
    public void close() {
        if (!temporary) return;
        try {
            if (Files.exists(root)) FileUtils.deleteRecursively(root);
            if (serverOverrides != null && Files.exists(serverOverrides)) FileUtils.deleteRecursively(serverOverrides);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
