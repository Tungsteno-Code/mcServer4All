package com.mcsync.world;

import java.nio.file.Path;

/** Where a modpack comes from when creating a world (same shape for GUI and CLI). */
public sealed interface ModpackSource {

    /** CurseForge export: {@code manifest.json} + {@code overrides/}. */
    record CurseForgeZip(Path zip) implements ModpackSource {
    }

    /** Modrinth {@code .mrpack}: {@code modrinth.index.json}. */
    record ModrinthMrpack(Path mrpack) implements ModpackSource {
    }

    /** An already working Prism instance folder (contains {@code mmc-pack.json}, {@code minecraft/}). */
    record PrismInstance(Path instanceDir) implements ModpackSource {
    }

    /** A plain folder with {@code mods/} and {@code config/}. */
    record Folder(Path dir) implements ModpackSource {
    }

    /** The file or folder the source points to. */
    default Path path() {
        return switch (this) {
            case CurseForgeZip z -> z.zip();
            case ModrinthMrpack m -> m.mrpack();
            case PrismInstance p -> p.instanceDir();
            case Folder f -> f.dir();
        };
    }
}
