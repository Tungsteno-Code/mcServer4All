package com.mcsync.world;

import com.mcsync.MainOrchestrator;
import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.core.StorageSyncManager;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.model.*;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Multi-world operations: create a world, export / import an invite, list worlds with their live
 * status, update the modpack (with the world lock, diff, backup, conditional write) and roll back.
 *
 * <p>Every world is an independent entity whose storage keys live under {@code worlds/<id>/}; the
 * rest of the launcher keeps working with relative keys on a world-derived {@link Config}.</p>
 */
public final class WorldService {

    private static final Logger log = LoggerFactory.getLogger(WorldService.class);

    /** Status of a world as shown in the "Unisciti al mondo" list. */
    public enum Live { FREE, STARTING, ONLINE, STOPPING, STALE, UNKNOWN }

    public record WorldStatus(WorldProfile profile, Live live, String host, boolean updateAvailable,
                              long remoteModpackVersion, long appliedModpackVersion, String error) {
        public String label() {
            String s = switch (live) {
                case FREE -> "libero";
                case STARTING -> "avvio in corso (" + host + ")";
                case ONLINE -> "online di " + host;
                case STOPPING -> "chiusura in corso (" + host + ")";
                case STALE -> "lock scaduto di " + host;
                case UNKNOWN -> "stato non disponibile";
            };
            return s + (updateAvailable ? " - aggiornamento disponibile" : "");
        }
    }

    /** Called with the diff before an update is published: return false to abort. */
    public interface Confirm extends Function<LockDiff, Boolean> {
    }

    private final Path configFile;
    private final Path baseDir;
    private volatile Config config;
    private final String gitExe;
    private final SourceResolver resolver;
    private final Supplier<Path> prismExe;
    private final Consumer<String> progress;

    public WorldService(Path configFile, Config config, String gitExe, SourceResolver resolver,
                        Supplier<Path> prismExe, Consumer<String> progress) {
        this.configFile = configFile.toAbsolutePath().normalize();
        this.baseDir = this.configFile.getParent() != null ? this.configFile.getParent() : Path.of(".").toAbsolutePath().normalize();
        this.config = config;
        this.gitExe = gitExe == null ? "git" : gitExe;
        this.resolver = resolver == null ? SourceResolver.NONE : resolver;
        this.prismExe = prismExe == null ? () -> null : prismExe;
        this.progress = progress == null ? m -> log.info("{}", m) : progress;
    }

    public Config config() {
        return config;
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path cacheDir() {
        return baseDir.resolve(".mcsync").resolve("cache");
    }

    private void say(String msg) {
        progress.accept(msg);
    }

    // ------------------------------------------------------------------------------------------
    // Derived configuration
    // ------------------------------------------------------------------------------------------

    /** Config used to operate on {@code w}: world prefix, world folders, recipe values, LOCKED mode. */
    public Config configFor(WorldProfile w) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("modSyncMode", "LOCKED");
        extra.put("cacheDirectory", config.cacheDirectory().isBlank() ? cacheDir().toString() : config.cacheDirectory());
        Optional<WorldRecipe> recipe = LockedModsSync.localRecipe(Path.of(Config.expandHome(w.serverDirectory())));
        if (recipe.isPresent()) {
            WorldRecipe r = recipe.get();
            extra.put("serverPort", r.port());
            extra.put("whitelistPlayers", List.of());
            extra.put("worldFolderName", r.worldFolderName());
            try {
                List<String> cmd = ServerInstaller.startCommand(r, Path.of(Config.expandHome(w.serverDirectory())));
                if (!cmd.isEmpty()) extra.put("serverStartCommand", cmd);
            } catch (IOException ignored) {
                // keep the configured command
            }
        }
        if (!w.prismInstanceId().isBlank()) {
            extra.put("clientLaunchCommand", PrismInstanceWriter.launchCommand(prismExe.get(), w.id()));
        }
        return config.forWorld(w, extra);
    }

    public Config configFor(String worldId) {
        return configFor(config.findWorld(worldId).orElseThrow(() ->
                new IllegalArgumentException("Mondo sconosciuto: " + worldId + " (mondi: " + worldIds() + ")")));
    }

    public List<String> worldIds() {
        return config.worlds().stream().map(WorldProfile::id).toList();
    }

    private S3Storage storage(Config c) {
        return new S3Storage(c, gitExe);
    }

    private Path serversRoot() {
        return config.serversRoot().isBlank() ? baseDir.resolve("servers")
                : Path.of(Config.expandHome(config.serversRoot())).toAbsolutePath().normalize();
    }

    private ModDownloader downloader(Config wc, WorldStore store) {
        return new ModDownloader(new ModCache(LockedModsSync.cacheDir(wc)), store, wc.curseforgeApiKey());
    }

    // ------------------------------------------------------------------------------------------
    // Config persistence
    // ------------------------------------------------------------------------------------------

    private void saveWorlds(Config updated) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worlds", updated.worlds());
        m.put("activeWorldId", updated.activeWorldId());
        new ConfigFixer(configFile).set(m);
        this.config = updated;
    }

    /** Selects the world the dashboard / {@code run} operate on. */
    public void setActiveWorld(String id) throws IOException {
        config.findWorld(id).orElseThrow(() -> new IllegalArgumentException("Mondo sconosciuto: " + id));
        saveWorlds(config.withActiveWorld(id));
    }

    /** Forgets a world locally (the repo is untouched); optionally deletes server folder and Prism instance. */
    public void removeLocal(String id, boolean deleteFiles) throws IOException {
        WorldProfile w = config.findWorld(id).orElseThrow(() -> new IllegalArgumentException("Mondo sconosciuto: " + id));
        if (deleteFiles) {
            Path dir = Path.of(Config.expandHome(w.serverDirectory())).toAbsolutePath().normalize();
            if (Files.isDirectory(dir) && dir.startsWith(serversRoot())) FileUtils.deleteRecursively(dir);
            if (!w.prismInstanceId().isBlank()) new PrismInstanceWriter(PrismInstanceWriter.locatePrismData(baseDir)).delete(id);
        }
        saveWorlds(config.withoutWorld(id));
    }

    // ------------------------------------------------------------------------------------------
    // 1. Create world
    // ------------------------------------------------------------------------------------------

    /** Creates the world described by {@code req}; returns its profile. */
    public WorldProfile create(CreateWorldRequest req) throws IOException {
        if (req.clientPack() == null) throw new IOException("Il modpack client è obbligatorio");
        String name = req.displayName() == null || req.displayName().isBlank() ? req.worldId() : req.displayName().trim();
        String id = req.worldId() == null || req.worldId().isBlank() ? WorldProfile.slugify(name) : req.worldId().trim();
        if (!WorldProfile.isValidId(id)) throw new IOException("Identificativo mondo non valido: '" + id
                + "' (minuscole, cifre, '-' e '_', max 48 caratteri)");
        if (config.findWorld(id).isPresent()) throw new IOException("Esiste già un mondo '" + id + "' su questo PC");
        if (name == null || name.isBlank()) name = id;

        Path serverDir = serversRoot().resolve(id);
        WorldProfile profile = new WorldProfile(id, name, serverDir.toString(), "", "", "CREATOR");
        Config wc = configFor(profile);
        Path work = baseDir.resolve(".mcsync").resolve("tmp").resolve(id + "-" + System.currentTimeMillis());
        try (S3Storage root = storage(config); S3Storage st = storage(wc)) {
            say("Controllo accesso al repository ...");
            root.checkAccess();
            WorldStore store = new WorldStore(st);
            if (WorldRegistry.read(root).contains(id) || store.lock().isPresent()) {
                throw new IOException("Il mondo '" + id + "' esiste già nel repository: scegli un altro nome");
            }

            say("Lettura del modpack ...");
            PackReader reader = new PackReader(config.curseforgeApiKey());
            Path serverPackRoot = null;
            try (PackStage client = reader.read(req.clientPack(), work)) {
                serverPackRoot = prepareServerPack(req.serverPack(), work);
                say("Minecraft " + client.minecraft() + " + " + client.loader().type() + " " + client.loader().version());
                say("Calcolo hash e ricerca delle sorgenti di download ...");
                LockBuilder lb = new LockBuilder(resolver, req.extraClientOnly(), req.pinnedConfigGlobs(), config.modSyncExcludes());
                LockBuilder.Built built = lb.build(client, serverPackRoot, 1, config.playerId());
                built.notes().forEach(this::say);

                WorldRecipe recipe = new WorldRecipe(WorldRecipe.SCHEMA_VERSION, id, name, client.minecraft(), client.loader(),
                        21, List.of(), req.port(), List.of(),
                        req.ramMinMb(), req.ramMaxMb(), "world", config.playerId());

                say("Pubblicazione nel repository (worlds/" + id + "/) ...");
                WorldStore.LockRef ref = store.publishNew(recipe, built.lock(), built.blobs());
                WorldRegistry.register(root, new WorldRegistry.Entry(id, name, config.playerId(), Instant.now().getEpochSecond()));

                say("Preparazione locale (cache, server, istanza Prism) ...");
                ModCache cache = new ModCache(LockedModsSync.cacheDir(wc));
                for (Map.Entry<String, Path> e : built.allFiles().entrySet()) cache.put(e.getKey(), e.getValue());
                profile = setupLocal(profile, wc, recipe, ref.lock(), store, true);
            } finally {
                cleanupTemp(work);
            }
        }
        saveWorlds(config.withWorld(profile).withActiveWorld(id));
        say("Mondo '" + name + "' creato.");
        return profile;
    }

    private Path prepareServerPack(ModpackSource src, Path work) throws IOException {
        if (src == null) return null;
        Path p = src.path();
        if (p == null || !Files.exists(p)) throw new IOException("Server pack non trovato: " + p);
        switch (src) {
            case ModpackSource.Folder f -> {
                return f.dir();
            }
            case ModpackSource.CurseForgeZip z -> {
                if (Zips.hasEntry(z.zip(), "manifest.json") && Zips.hasEntryWithPrefix(z.zip(), "overrides/")) {
                    throw new IOException("Lo zip indicato come server pack sembra un modpack client (manifest.json + overrides/)");
                }
                Path dir = Files.createTempDirectory(work(work), "srvpack-");
                Zips.extract(z.zip(), Zips.singleTopLevelPrefix(z.zip()), dir);
                return dir;
            }
            default -> throw new IOException("Il server pack deve essere una cartella o uno zip");
        }
    }

    private static Path work(Path w) throws IOException {
        Files.createDirectories(w);
        return w;
    }

    private static void cleanupTemp(Path work) {
        try {
            if (Files.exists(work)) FileUtils.deleteRecursively(work);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Local part shared by create and import: Prism instance + client mods; the server only when
     * {@code withServer} (creator / hosts do it lazily at start otherwise).
     */
    private WorldProfile setupLocal(WorldProfile profile, Config wc, WorldRecipe recipe, ModsLock lock, WorldStore store,
                                    boolean withServer) throws IOException {
        Path prismData = PrismInstanceWriter.locatePrismData(baseDir);
        Path game = new PrismInstanceWriter(prismData).ensure(profile.id(), profile.displayName(), lock.minecraft(),
                lock.loader(), recipe.ramMinMb(), recipe.ramMaxMb());
        profile = profile.withPrism(PrismInstanceWriter.instanceId(profile.id()), game.toString());
        Config wc2 = configFor(profile);
        LocalStateStore state = new LocalStateStore(wc2.stateDir());
        ModDownloader dl = new ModDownloader(new ModCache(LockedModsSync.cacheDir(wc2)), store, wc2.curseforgeApiKey())
                .withProgress((done, total, file) ->
                        say("Download mod [" + done + "/" + total + "] - " + ((done * 100) / Math.max(1, total)) + "% (" + file + ")"));
        LockMaterializer mat = new LockMaterializer(dl);
        if (withServer) {
            say("Installazione del server " + recipe.loader().type() + " " + recipe.loader().version() + " ...");
            new ServerInstaller(mat, () -> new LockedModsSync(wc2, store.storage(), state).javaBin())
                    .ensure(recipe, lock, wc2.serverDir(), state);
        }
        say("Applicazione del modpack al client ...");
        LockMaterializer.Result r = mat.materializeClient(lock, game, true);
        String hash = lock.contentHash();
        state.update(s -> s.withAppliedLock("client", hash, lock.modpackVersion()));
        Files.createDirectories(wc2.stateDir());
        Files.write(wc2.stateDir().resolve(LockedModsSync.LOCAL_RECIPE), Json.toBytes(recipe));
        say(r.downloaded() + " mod scaricate, " + r.linked() + " collegate nell'istanza Prism");
        return profile;
    }

    // ------------------------------------------------------------------------------------------
    // 2. Invites
    // ------------------------------------------------------------------------------------------

    /** Writes the {@code .mcsync} invite of {@code worldId}. The file contains the repo token in clear text. */
    public Path exportInvite(String worldId, Path target) throws IOException {
        WorldProfile w = config.findWorld(worldId).orElseThrow(() -> new IOException("Mondo sconosciuto: " + worldId));
        Config wc = configFor(w);
        try (S3Storage st = storage(wc)) {
            WorldStore store = new WorldStore(st);
            WorldRecipe recipe = store.recipe().orElseThrow(() -> new IOException("world.json mancante nel repo"));
            WorldStore.LockRef ref = store.lock().orElseThrow(() -> new IOException("mods.lock.json mancante nel repo"));
            Invite inv = InviteService.build(config, w, recipe, ref, MainOrchestrator.VERSION);
            Path out = target.toAbsolutePath().normalize();
            if (Files.isDirectory(out)) out = out.resolve(worldId + ".mcsync");
            InviteService.write(inv, out);
            say("Invito scritto in " + out + " (contiene il token del repository: non condividerlo in pubblico)");
            return out;
        }
    }

    /** Imports an invite: registers the world, downloads the mods, creates the Prism instance. */
    public WorldProfile importInvite(Path file) throws IOException {
        Invite inv = InviteService.read(file, MainOrchestrator.VERSION);
        String id = inv.worldId();
        if (config.findWorld(id).isPresent()) throw new IOException("Il mondo '" + id + "' è già presente su questo PC");

        Invite.Storage st = inv.storage();
        Map<String, Object> globals = new LinkedHashMap<>();
        Invite.Storage override = null;
        boolean hasRepo = config.isGitBackend() ? !config.gitRemoteUrl().isBlank() : !config.s3Bucket().isBlank();
        if (!hasRepo) {
            // first repo of this PC: adopt it globally (token moves into config.json, never into logs)
            globals.put("storageBackend", st.backend().toUpperCase(Locale.ROOT));
            if ("GIT".equalsIgnoreCase(st.backend())) {
                globals.put("gitRemoteUrl", st.remoteUrl());
                globals.put("gitBranch", st.branch() == null ? "mcsync-state" : st.branch());
                globals.put("gitToken", st.token() == null ? "" : st.token());
            } else {
                globals.put("s3Endpoint", st.s3Endpoint());
                globals.put("s3Region", st.s3Region() == null ? "auto" : st.s3Region());
                globals.put("s3Bucket", st.s3Bucket());
                globals.put("s3AccessKey", st.s3AccessKey() == null ? "" : st.s3AccessKey());
                globals.put("s3SecretKey", st.s3SecretKey() == null ? "" : st.s3SecretKey());
                if (st.s3PathStyle() != null) globals.put("s3PathStyle", st.s3PathStyle());
            }
            if (st.prefix() != null && !st.prefix().isBlank()) globals.put("s3KeyPrefix", st.prefix());
        } else if (!InviteService.sameRepo(config, st)) {
            override = st;          // another repo: remembered per world
        } else if (config.isGitBackend() && config.gitToken().isBlank() && st.token() != null) {
            globals.put("gitToken", st.token());
        }
        if (config.curseforgeApiKey().isBlank() && inv.curseforgeApiKey() != null && !inv.curseforgeApiKey().isBlank()) {
            globals.put("curseforgeApiKey", inv.curseforgeApiKey().trim());
        }

        Path serverDir = serversRoot().resolve(id);
        WorldProfile profile = new WorldProfile(id, inv.displayName(), serverDir.toString(), "", "", "MEMBER", override);
        Config base = globals.isEmpty() ? config : config.with(globals);
        Config saved = this.config;
        this.config = base;                    // so that configFor() sees the adopted repo
        try {
            Config wc = configFor(profile);
            try (S3Storage storage = storage(wc)) {
                say("Verifica dell'accesso al repository ...");
                storage.checkAccess();
                WorldStore store = new WorldStore(storage);
                WorldStore.LockRef ref = store.lock().orElseThrow(() ->
                        new IOException("Il mondo '" + id + "' non esiste nel repository dell'invito"));
                if (ref.lock().modpackVersion() == inv.modpackVersion() && inv.lockSha256() != null
                        && !inv.lockSha256().equalsIgnoreCase(ref.sha256())) {
                    throw new IOException("Il modpack nel repository non corrisponde all'invito (lockSha256 diverso): "
                            + "file d'invito o repository manomessi");
                }
                WorldRecipe recipe = store.recipe().orElseThrow(() -> new IOException("world.json mancante nel repo"));
                say("Download del modpack v" + ref.lock().modpackVersion() + " e creazione istanza Prism ...");
                profile = setupLocal(profile, wc, recipe, ref.lock(), store, false);
            }
        } catch (IOException | RuntimeException e) {
            this.config = saved;
            throw e;
        }
        if (!globals.isEmpty()) {
            Map<String, Object> g = new LinkedHashMap<>(globals);
            new ConfigFixer(configFile).set(castScalar(g));
        }
        saveWorlds(config.withWorld(profile).withActiveWorld(id));
        say("Mondo '" + profile.displayName() + "' importato: ora compare nella lista \"Unisciti\".");
        return profile;
    }

    private static Map<String, Object> castScalar(Map<String, Object> m) {
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // 3. List with live status
    // ------------------------------------------------------------------------------------------

    public List<WorldStatus> listWorlds() {
        List<WorldStatus> out = new ArrayList<>();
        for (WorldProfile w : config.worlds()) out.add(status(w));
        return out;
    }

    public WorldStatus status(WorldProfile w) {
        Config wc = configFor(w);
        long applied = 0;
        try {
            LocalState ls = new LocalStateStore(wc.stateDir()).get();
            applied = ls.modpackVersion();
            try (S3Storage st = storage(wc); LockManager lm = new LockManager(wc, st)) {
                LockManager.Inspection insp = lm.inspect();
                Live live;
                String host = null;
                switch (insp.state()) {
                    case FREE -> live = Live.FREE;
                    case OWN_LEFTOVER -> {
                        if (com.mcsync.MainOrchestrator.isSessionActive()) {
                            host = insp.lock().ownerId();
                            live = switch (insp.lock().status()) {
                                case STARTING -> Live.STARTING;
                                case STOPPING -> Live.STOPPING;
                                default -> Live.ONLINE;
                            };
                        } else {
                            // Leftover lock owned by this player while no local session is running: clean it up!
                            boolean hasValidLocalWorld = Files.isRegularFile(wc.worldDir().resolve("level.dat"));
                            if (!ls.dirty() || !hasValidLocalWorld) {
                                try {
                                    st.delete(LockManager.LOCK_KEY);
                                } catch (Exception ignored) {
                                }
                            }
                            live = Live.FREE;
                            host = null;
                        }
                    }
                    case STALE -> {
                        live = Live.STALE;
                        host = insp.lock().ownerId();
                    }
                    default -> {
                        host = insp.lock().ownerId();
                        live = switch (insp.lock().status()) {
                            case STARTING -> Live.STARTING;
                            case STOPPING -> Live.STOPPING;
                            default -> Live.ONLINE;
                        };
                    }
                }
                Optional<WorldStore.LockRef> ref = new WorldStore(st).lock();
                boolean upd = ref.isPresent()
                        && !ref.get().lock().contentHash().equals(ls.appliedClientLockHash())
                        && !ref.get().lock().contentHash().equals(ls.appliedServerLockHash());
                return new WorldStatus(w, live, host, upd, ref.map(r -> r.lock().modpackVersion()).orElse(0L), applied, null);
            }
        } catch (Exception e) {
            return new WorldStatus(w, Live.UNKNOWN, null, false, 0, applied, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. Update modpack / rollback
    // ------------------------------------------------------------------------------------------

    /** Options of {@link #updateModpack}. */
    public record UpdateOptions(ModpackSource serverPack, boolean backup, List<String> extraClientOnly,
                                List<String> pinnedConfigGlobs) {
        public static UpdateOptions defaults() {
            return new UpdateOptions(null, true, List.of(), List.of());
        }
    }

    /**
     * Publishes a new modpack version. Needs the world lock (refused while somebody hosts), asks
     * {@code confirm} with the diff, makes a pre-update backup of the world and writes the lock with
     * {@code putIfMatch}.
     *
     * @return the new lock version, or {@code -1} if the user declined at the diff
     */
    public long updateModpack(String worldId, ModpackSource pack, UpdateOptions opt, Confirm confirm) throws IOException {
        WorldProfile w = config.findWorld(worldId).orElseThrow(() -> new IOException("Mondo sconosciuto: " + worldId));
        Config wc = configFor(w);
        Path work = baseDir.resolve(".mcsync").resolve("tmp").resolve(worldId + "-upd-" + System.currentTimeMillis());
        try (S3Storage st = storage(wc); LockManager lm = new LockManager(wc, st)) {
            WorldStore store = new WorldStore(st);
            acquireWorldLock(lm);
            try {
                WorldStore.LockRef base = store.lock().orElseThrow(() -> new IOException("Il mondo non ha ancora un modpack"));
                WorldRecipe recipe = store.recipe().orElseThrow(() -> new IOException("world.json mancante"));
                say("Lettura del nuovo modpack ...");
                try (PackStage client = new PackReader(config.curseforgeApiKey()).read(pack, work)) {
                    Path serverRoot = prepareServerPack(opt.serverPack(), work);
                    LockBuilder lb = new LockBuilder(resolver, opt.extraClientOnly(), opt.pinnedConfigGlobs(), config.modSyncExcludes());
                    LockBuilder.Built built = lb.build(client, serverRoot, base.lock().modpackVersion() + 1, config.playerId());
                    LockDiff diff = LockDiff.of(base.lock(), built.lock());
                    if (diff.isEmpty()) {
                        say("Nessuna differenza rispetto alla v" + base.lock().modpackVersion() + ": niente da pubblicare");
                        return base.lock().modpackVersion();
                    }
                    say("Differenze rispetto alla v" + base.lock().modpackVersion() + ":" + System.lineSeparator() + diff.render());
                    if (confirm != null && !confirm.apply(diff)) {
                        say("Aggiornamento annullato");
                        return -1;
                    }
                    if (opt.backup()) preUpdateBackup(wc, st, built.lock().modpackVersion());
                    WorldRecipe newRecipe = client.minecraft().equals(recipe.minecraft()) && client.loader().equals(recipe.loader())
                            ? null : recipe.withPlatform(client.minecraft(), client.loader());
                    WorldStore.LockRef ref;
                    try {
                        ref = store.update(built.lock(), built.blobs(), base, newRecipe);
                    } catch (WorldStore.ConflictException e) {
                        throw new IOException(e.getMessage() + " (riprova: il modpack nel repo è cambiato)", e);
                    }
                    ModCache cache = new ModCache(LockedModsSync.cacheDir(wc));
                    for (Map.Entry<String, Path> e : built.allFiles().entrySet()) cache.put(e.getKey(), e.getValue());
                    WorldRecipe effective = newRecipe != null ? newRecipe : recipe;
                    setupLocal(w, wc, effective, ref.lock(), store, false);
                    new com.mcsync.notify.DiscordNotifier(wc).modpackUpdated(ref.lock().modpackVersion(), config.playerId());
                    say("Modpack aggiornato alla v" + ref.lock().modpackVersion() + ". Gli altri lo riceveranno al prossimo accesso.");
                    return ref.lock().modpackVersion();
                }
            } finally {
                lm.release();
                cleanupTemp(work);
            }
        }
    }

    /** Re-publishes an archived lock version as the newest one (one click rollback). */
    public long rollback(String worldId, long targetVersion) throws IOException {
        WorldProfile w = config.findWorld(worldId).orElseThrow(() -> new IOException("Mondo sconosciuto: " + worldId));
        Config wc = configFor(w);
        try (S3Storage st = storage(wc); LockManager lm = new LockManager(wc, st)) {
            WorldStore store = new WorldStore(st);
            acquireWorldLock(lm);
            try {
                WorldStore.LockRef base = store.lock().orElseThrow(() -> new IOException("Il mondo non ha un modpack"));
                if (targetVersion == base.lock().modpackVersion()) throw new IOException("v" + targetVersion + " è già la versione corrente");
                preUpdateBackup(wc, st, base.lock().modpackVersion() + 1);
                WorldStore.LockRef ref = store.rollback(targetVersion, base, config.playerId());
                WorldRecipe recipe = store.recipe().orElseThrow(() -> new IOException("world.json mancante"));
                if (!Objects.equals(recipe.minecraft(), ref.lock().minecraft()) || !Objects.equals(recipe.loader(), ref.lock().loader())) {
                    recipe = recipe.withPlatform(ref.lock().minecraft(), ref.lock().loader());
                    store.putRecipe(recipe);
                }
                setupLocal(w, wc, recipe, ref.lock(), store, false);
                new com.mcsync.notify.DiscordNotifier(wc).modpackUpdated(ref.lock().modpackVersion(), config.playerId());
                say("Rollback eseguito: il contenuto della v" + targetVersion + " è ora la v" + ref.lock().modpackVersion());
                return ref.lock().modpackVersion();
            } finally {
                lm.release();
            }
        }
    }

    /** Lists the lock versions available for rollback (oldest first), plus the current one. */
    public List<Long> modpackVersions(String worldId) throws IOException {
        Config wc = configFor(worldId);
        try (S3Storage st = storage(wc)) {
            WorldStore store = new WorldStore(st);
            List<Long> out = new ArrayList<>(store.lockVersions());
            store.lock().ifPresent(l -> {
                if (!out.contains(l.lock().modpackVersion())) out.add(l.lock().modpackVersion());
            });
            return out;
        }
    }

    private void acquireWorldLock(LockManager lm) throws IOException {
        LockManager.Inspection insp = lm.inspect();
        switch (insp.state()) {
            case FREE -> {
                if (!lm.tryAcquire()) throw new IOException("Qualcuno ha appena avviato il mondo: riprova a fine sessione");
            }
            case OWN_LEFTOVER -> {
                if (!lm.breakAndAcquire(insp)) throw new IOException("Impossibile acquisire il lock del mondo");
            }
            case HELD -> throw new IOException(insp.lock().ownerId() + " sta ospitando il mondo: riprova a fine sessione");
            case STALE -> throw new IOException("Il lock di " + insp.lock().ownerId()
                    + " è scaduto: usa 'force-unlock' sul mondo e riprova");
        }
        lm.startHeartbeat(lost -> log.error("Lock del mondo perso durante l'aggiornamento"));
    }

    /**
     * Backup of the world before a modpack change (removing mods can corrupt the save): brings the
     * local copy up to date, archives it under {@code .mcsync/pre-update-backups/} and, on S3, also
     * stores it in {@code backups/}.
     */
    Path preUpdateBackup(Config wc, S3Storage st, long newModpackVersion) throws IOException {
        LocalStateStore state = new LocalStateStore(wc.stateDir());
        StorageSyncManager sync = new StorageSyncManager(wc, st, state);
        Optional<WorldManifest> remote = sync.fetchRemoteManifest();
        if (remote.isEmpty()) {
            say("Nessun mondo salvato ancora: backup pre-aggiornamento non necessario");
            return null;
        }
        say("Backup pre-aggiornamento del mondo (v" + remote.get().version() + ") ...");
        if (!Files.isDirectory(wc.worldDir()) || state.get().dirty() || remote.get().version() != state.get().worldVersion()) {
            sync.pullWorld(remote.get());
        }
        Path target = wc.stateDir().resolve("pre-update-backups").resolve("pre-update-modpack-v" + newModpackVersion
                + "-world-v" + remote.get().version() + "-" + Instant.now().getEpochSecond() + ".tar.zst");
        StorageSyncManager.archive(wc.worldDir(), target);
        st.uploadFile(StorageSyncManager.BACKUP_PREFIX + target.getFileName(), target, "application/zstd");
        sync.pruneBackups();
        say("Backup salvato in " + target);
        return target;
    }
}
