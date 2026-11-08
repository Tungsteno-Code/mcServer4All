package com.mcsync.core;

import com.mcsync.world.ModCache;
import com.mcsync.world.WorldStore;

import java.io.IOException;
import java.util.Collection;

/**
 * Parallel downloader from Modrinth / CurseForge / repository blob store with SHA-256 verification
 * and shared local cache ({@code .mcsync/cache/modfiles/<sha256>}).
 *
 * <p>Facade in {@code com.mcsync.core} delegating to {@link com.mcsync.world.ModDownloader}.</p>
 */
public final class ModDownloader {

    private final com.mcsync.world.ModDownloader delegate;

    public ModDownloader(ModCache cache, WorldStore store, String curseforgeApiKey) {
        this.delegate = new com.mcsync.world.ModDownloader(cache, store, curseforgeApiKey);
    }

    public ModDownloader(ModCache cache, WorldStore store, String curseforgeApiKey, String curseforgeApiBase, int parallelism) {
        this.delegate = new com.mcsync.world.ModDownloader(cache, store, curseforgeApiKey, curseforgeApiBase, parallelism);
    }

    public ModCache cache() {
        return delegate.cache();
    }

    public int ensureAll(Collection<com.mcsync.world.ModDownloader.Wanted> wanted) throws IOException {
        return delegate.ensureAll(wanted);
    }

    public com.mcsync.world.ModDownloader unwrap() {
        return delegate;
    }
}
