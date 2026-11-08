package com.mcsync.world;

import com.mcsync.config.Config;
import com.mcsync.model.ModsLock;
import com.mcsync.model.WorldRecipe;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code modSyncMode = LOCKED}: no scan, no merge, no hashing of {@code mods/}. Host and clients
 * materialise the world's {@code mods.lock.json}. Each start compares a single value (the lock content
 * hash) with the one recorded in {@code LocalState}: when equal nothing else is done.
 */
public final class LockedModsSync {

    private static final Logger log = LoggerFactory.getLogger(LockedModsSync.class);

    /** Name of the cached recipe copy in {@code <serverDir>/.mcsync/}. */
    public static final String LOCAL_RECIPE = "world.json";

    private final Config cfg;
    private final LocalStateStore state;
    private final WorldStore store;
    private final ModCache cache;
    private final ModDownloader downloader;
    private final LockMaterializer materializer;

    public LockedModsSync(Config cfg, S3Storage storage, LocalStateStore state) {
        this.cfg = cfg;
        this.state = state;
        this.store = new WorldStore(storage);
        this.cache = new ModCache(cacheDir(cfg));
        this.downloader = new ModDownloader(cache, store, cfg.curseforgeApiKey());
        this.materializer = new LockMaterializer(downloader);
    }

    public static Path cacheDir(Config cfg) {
        if (!cfg.cacheDirectory().isBlank()) return Path.of(Config.expandHome(cfg.cacheDirectory())).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), ".mcsync", "cache");
    }

    public ModCache cache() {
        return cache;
    }

    public Path javaBin() {
        String exe = FileUtils.IS_WINDOWS ? "java.exe" : "java";
        Path home = cfg.serverJavaHome().isBlank() ? Path.of(System.getProperty("java.home")) : Path.of(cfg.serverJavaHome());
        return home.resolve("bin").resolve(exe);
    }

    private WorldStore.LockRef requireLock() throws IOException {
        return store.lock().orElseThrow(() -> new IOException("Il mondo '" + cfg.activeWorldId()
                + "' non ha un modpack bloccato (mods.lock.json) nel repo: crealo o importa l'invito"));
    }

    // ------------------------------------------------------------------------------------------
    // Host
    // ------------------------------------------------------------------------------------------

    /** Builds / refreshes the local server from the recipe + lock. @return the start command */
    public List<String> syncAsHost() throws IOException {
        WorldRecipe recipe = store.recipe().orElseThrow(() -> new IOException("world.json mancante per il mondo '"
                + cfg.activeWorldId() + "'"));
        WorldStore.LockRef ref = requireLock();
        ModsLock lock = ref.lock();
        ServerInstaller installer = new ServerInstaller(materializer, this::javaBin);
        ServerInstaller.Result r = installer.ensure(recipe, lock, cfg.serverDir(), state);
        saveLocalRecipe(recipe);
        List<String> cmd = new ArrayList<>(r.startCommand());
        if (!cmd.isEmpty() && (cmd.get(0).equals("java") || cmd.get(0).equals("java.exe"))) cmd.set(0, javaBin().toString());
        return cmd;
    }

    // ------------------------------------------------------------------------------------------
    // Client
    // ------------------------------------------------------------------------------------------

    /** Ensures the Prism instance of the world has exactly the locked mods. */
    public void syncAsClient() throws IOException {
        Path game = cfg.clientInstanceDir();
        if (game == null) {
            log.info("Nessuna istanza client configurata per il mondo: sync mod client saltata");
            return;
        }
        WorldStore.LockRef ref = requireLock();
        ModsLock lock = ref.lock();
        String hash = lock.contentHash();
        if (hash.equals(state.get().appliedClientLockHash()) && allClientJarsPresent(lock, game)) {
            log.info("[client] Modpack bloccato v{} già applicato: nessuna azione", lock.modpackVersion());
            return;
        }
        ensurePrismInstance(lock, game);
        LockMaterializer.Result r = materializer.materializeClient(lock, game, true);
        state.update(s -> s.withAppliedLock("client", hash, lock.modpackVersion()));
        log.info("[client] Modpack v{} applicato: {} scaricati, {} collegati, {} rimossi, {} config", lock.modpackVersion(),
                r.downloaded(), r.linked(), r.removed(), r.configs());
    }

    /** Cheap check, no hashing: every jar of the client side exists with the right size. */
    boolean allClientJarsPresent(ModsLock lock, Path game) {
        for (var e : lock.files().entrySet()) {
            if (!e.getValue().onClient()) continue;
            Path p = game.resolve(e.getKey());
            try {
                if (!Files.isRegularFile(p) || Files.size(p) != e.getValue().size()) return false;
            } catch (IOException ex) {
                return false;
            }
        }
        return true;
    }

    private void ensurePrismInstance(ModsLock lock, Path game) throws IOException {
        Path inst = game.getParent();
        if (inst == null || !inst.getFileName().toString().startsWith("mcsync-")) return; // not ours (legacy world)
        Path instances = inst.getParent();
        if (instances == null || instances.getParent() == null) return;
        String worldId = inst.getFileName().toString().substring("mcsync-".length());
        Optional<WorldRecipe> recipe = store.recipe();
        new PrismInstanceWriter(instances.getParent()).ensure(worldId,
                recipe.map(WorldRecipe::displayName).orElse(worldId), lock.minecraft(), lock.loader(),
                recipe.map(WorldRecipe::ramMinMb).orElse(2048), recipe.map(WorldRecipe::ramMaxMb).orElse(4096));
    }

    /** Cheap check used by the GUI: does the remote lock differ from what this PC applied? */
    public boolean hasPendingUpdates() {
        try {
            Optional<WorldStore.LockRef> ref = store.lock();
            if (ref.isEmpty()) return false;
            String h = ref.get().lock().contentHash();
            return !h.equals(state.get().appliedClientLockHash()) && !h.equals(state.get().appliedServerLockHash());
        } catch (IOException e) {
            log.debug("Controllo aggiornamenti fallito: {}", e.getMessage());
            return false;
        }
    }

    private void saveLocalRecipe(WorldRecipe recipe) throws IOException {
        Path f = cfg.stateDir().resolve(LOCAL_RECIPE);
        Files.createDirectories(f.getParent());
        Files.write(f, Json.toBytes(recipe));
    }

    /** Recipe cached by the last successful host/create/import on this PC. */
    public static Optional<WorldRecipe> localRecipe(Path serverDir) {
        try {
            Path f = serverDir.resolve(".mcsync").resolve(LOCAL_RECIPE);
            if (!Files.isRegularFile(f)) return Optional.empty();
            return Optional.of(Json.fromBytes(Files.readAllBytes(f), WorldRecipe.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
