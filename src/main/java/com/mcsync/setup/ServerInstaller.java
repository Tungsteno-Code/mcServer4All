package com.mcsync.setup;

import com.mcsync.model.ModsLock;
import com.mcsync.model.WorldRecipe;
import com.mcsync.util.LocalStateStore;
import com.mcsync.world.LockMaterializer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Idempotent per-world server installer (§7 &amp; §11 of the plan).
 *
 * <p>Compares the recipe + lock hash with {@code installedServerRecipeHash} in {@code LocalState};
 * when unchanged, skips all work. Otherwise downloads and runs the official loader installer
 * (Forge, NeoForge, Fabric, Quilt), sanitises startup scripts ({@code prepareServerPack}) and
 * materialises {@code mods/} and {@code serverfiles/}.</p>
 */
public final class ServerInstaller {

    private final com.mcsync.world.ServerInstaller delegate;

    public ServerInstaller(LockMaterializer materializer, Supplier<Path> javaBin) {
        this.delegate = new com.mcsync.world.ServerInstaller(materializer, javaBin);
    }

    public ServerInstaller(
            LockMaterializer materializer,
            Supplier<Path> javaBin,
            com.mcsync.world.ServerInstaller.Fetcher fetcher,
            com.mcsync.world.ServerInstaller.Runner runner) {
        this.delegate = new com.mcsync.world.ServerInstaller(materializer, javaBin, fetcher, runner);
    }

    public static String recipeHash(WorldRecipe recipe, ModsLock lock) {
        return com.mcsync.world.ServerInstaller.recipeHash(recipe, lock);
    }

    public com.mcsync.world.ServerInstaller.Result ensure(
            WorldRecipe recipe, ModsLock lock, Path serverDir, LocalStateStore state) throws IOException {
        return delegate.ensure(recipe, lock, serverDir, state);
    }
}
