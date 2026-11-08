package com.mcsync.gui.controller;

import com.mcsync.MainOrchestrator;
import com.mcsync.OrchestratorState;
import com.mcsync.config.Config;
import com.mcsync.core.LockManager;
import com.mcsync.core.ServerProcessController;
import com.mcsync.diagnostics.ConfigFixer;
import com.mcsync.diagnostics.LogAnalyzer;
import com.mcsync.model.LocalState;
import com.mcsync.model.LockFile;
import com.mcsync.model.WorldManifest;
import com.mcsync.setup.DependencyInstaller;
import com.mcsync.setup.PrismInstances;
import com.mcsync.setup.ServerProfileDetector;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.LocalStateStore;
import com.mcsync.util.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Coordinates the Swing UI and {@link MainOrchestrator}. All blocking operations run on background
 * daemon threads; UI callbacks are dispatched onto the Swing Event Dispatch Thread.
 */
public final class OrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorController.class);

    public record DashboardData(
            Config config,
            String configError,
            String tailscaleIp,
            boolean tailscaleConnected,
            LockManager.Inspection lock,
            Optional<WorldManifest> remoteWorld,
            LocalState localState,
            int backupsCount,
            boolean modUpdatesPending,
            String modpackName,
            ServerProfileDetector.Detection serverDetection,
            String remoteError
    ) {
    }

    public interface UiListener {
        default void onDashboardUpdated(DashboardData data) {
        }

        default void onStateChanged(OrchestratorState state) {
        }

        default void onServerConsoleLine(String line) {
        }

        default void onPlayersChanged(Set<String> players) {
        }

        default void onSessionFinished(int exitCode) {
        }

        default void onStatusMessage(String message, boolean isError) {
        }

        default void onProgressUpdated(int percent, String detail, boolean active) {
        }
    }

    private final Path configPath;
    private final ConfigFixer configFixer;
    private final List<UiListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService bg = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gui-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile Config currentConfig;
    private volatile MainOrchestrator activeOrchestrator;
    private volatile DashboardData lastDashboard;
    private volatile boolean sessionRunning;

    public OrchestratorController(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.configFixer = new ConfigFixer(this.configPath);
        ensureConfigFileExists();
    }

    private void ensureConfigFileExists() {
        if (!Files.exists(configPath)) {
            MainOrchestrator.initConfig(configPath);
        }
    }

    public Path configPath() {
        return configPath;
    }

    public ConfigFixer configFixer() {
        return configFixer;
    }

    public Config currentConfig() {
        return currentConfig;
    }

    public MainOrchestrator activeOrchestrator() {
        return activeOrchestrator;
    }

    public DashboardData lastDashboard() {
        return lastDashboard;
    }

    public boolean isSessionRunning() {
        return sessionRunning;
    }

    public void addListener(UiListener l) {
        listeners.add(l);
    }

    public Config reloadConfigQuietly() {
        try {
            Config c = Config.load(configPath);
            currentConfig = c;
            MainOrchestrator.enableFileLog(c);
            return c;
        } catch (Exception e) {
            return currentConfig;
        }
    }

    /** Refreshes the dashboard state asynchronously (Tailscale, Git/S3 lock, world manifest, mods). */
    public void refreshDashboardAsync() {
        bg.submit(() -> {
            DashboardData data = fetchDashboardData();
            lastDashboard = data;
            onEdt(() -> listeners.forEach(l -> l.onDashboardUpdated(data)));
        });
    }

    private DashboardData fetchDashboardData() {
        String detectedTs = NetUtils.detectTailscaleIp();
        Config cfg = null;
        String cfgErr = null;
        try {
            cfg = Config.load(configPath);
            if ("auto".equalsIgnoreCase(cfg.localTailscaleIp()) && detectedTs != null) {
                cfg = cfg.withLocalTailscaleIp(detectedTs);
            }
            currentConfig = cfg;
            MainOrchestrator.enableFileLog(cfg);
        } catch (Exception e) {
            cfgErr = e.getMessage();
        }

        String tsIp = detectedTs != null ? detectedTs
                : (cfg != null && !"auto".equalsIgnoreCase(cfg.localTailscaleIp()) ? cfg.localTailscaleIp() : null);
        boolean tsOk = tsIp != null && !tsIp.isBlank() && !"auto".equalsIgnoreCase(tsIp);

        if (cfg == null) {
            return new DashboardData(null, cfgErr, tsIp, tsOk, null, Optional.empty(),
                    LocalState.empty(), 0, false, "-",
                    new ServerProfileDetector.Detection(ServerProfileDetector.Profile.UNKNOWN, List.of(), null),
                    cfgErr);
        }

        DependencyInstaller installer = new DependencyInstaller(baseDir());
        String gitExe = cfg.isGitBackend() ? Optional.ofNullable(installer.findExistingGit()).orElse("git") : "git";
        if (cfg.activeWorld().isPresent()) {
            com.mcsync.world.WorldService ws = new com.mcsync.world.WorldService(
                    configPath, cfg, gitExe, com.mcsync.world.SourceResolver.combined(cfg.curseforgeApiKey()),
                    installer::findExistingPrismLauncher, null);
            cfg = ws.configFor(cfg.activeWorld().get());
        }
        String modpackName = resolveModpackName(cfg);
        ServerProfileDetector.Detection detection = ServerProfileDetector.detect(cfg.serverDir());
        LocalState local = new LocalStateStore(cfg.stateDir()).get();

        try (MainOrchestrator probe = new MainOrchestrator(cfg, false, gitExe)) {
            MainOrchestrator.Snapshot snap = probe.snapshot();
            return new DashboardData(cfg, null, tsIp, tsOk, snap.lock(), snap.remoteWorld(),
                    snap.local(), snap.backups(), snap.modUpdatesPending(), modpackName, detection, null);
        } catch (Exception e) {
            log.debug("Dashboard remote probe error: {}", e.getMessage());
            return new DashboardData(cfg, null, tsIp, tsOk, null, Optional.empty(),
                    local, 0, false, modpackName, detection, e.getMessage());
        }
    }

    private static String resolveModpackName(Config cfg) {
        Path clientDir = cfg.clientInstanceDir();
        String fromInst = PrismInstances.instanceName(clientDir);
        if (fromInst != null && !fromInst.isBlank()) return fromInst;
        List<String> cmd = cfg.clientLaunchCommand();
        int idx = cmd.indexOf("--launch");
        if (idx >= 0 && idx + 1 < cmd.size()) return cmd.get(idx + 1);
        Path sDir = cfg.serverDir();
        return sDir != null && sDir.getFileName() != null ? sDir.getFileName().toString() : "Modded World";
    }

    /** Starts the main session (Host or Client automatically) on a background thread. */
    public void startSession(boolean launchClient) {
        if (sessionRunning) {
            notifyStatus("Una sessione è già in esecuzione.", true);
            return;
        }
        sessionRunning = true;
        bg.submit(() -> {
            int code = 1;
            MainOrchestrator orch = null;
            try {
                notifyStatus("Preparazione ambiente e verifica dipendenze in corso...", false);
                MainOrchestrator.Prepared prep = MainOrchestrator.bootstrap(configPath, launchClient);
                currentConfig = prep.cfg();
                MainOrchestrator.enableFileLog(prep.cfg());

                orch = new MainOrchestrator(prep.cfg(), launchClient, prep.gitExecutable());
                orch.setConsoleInputEnabled(false);
                orch.setObserver(new MainOrchestrator.Observer() {
                    @Override
                    public void stateChanged(OrchestratorState state) {
                        onEdt(() -> listeners.forEach(l -> l.onStateChanged(state)));
                    }

                    @Override
                    public void serverLine(String line) {
                        onEdt(() -> listeners.forEach(l -> l.onServerConsoleLine(line)));
                    }

                    @Override
                    public void playersChanged(Set<String> players) {
                        onEdt(() -> listeners.forEach(l -> l.onPlayersChanged(players)));
                    }

                    @Override
                    public void joinedHost(LockFile lock) {
                        notifyStatus("Connesso al server di " + lock.ownerId() + " (" + lock.address() + ")", false);
                    }
                });
                activeOrchestrator = orch;
                code = orch.run();
            } catch (Exception e) {
                log.error("Errore sessione: {}", e.getMessage(), e);
                notifyStatus("Errore: " + e.getMessage(), true);
            } finally {
                if (orch != null) {
                    try {
                        orch.close();
                    } catch (Exception ignored) {
                    }
                }
                activeOrchestrator = null;
                sessionRunning = false;
                int finalCode = code;
                onEdt(() -> listeners.forEach(l -> l.onSessionFinished(finalCode)));
                refreshDashboardAsync();
            }
        });
    }

    /** Requests a graceful stop of the active hosting session. */
    public void requestStopSession() {
        MainOrchestrator orch = activeOrchestrator;
        if (orch != null) {
            notifyStatus("Arresto del server e salvataggio del mondo in corso...", false);
            bg.submit(() -> orch.requestStop("arresto richiesto dalla GUI"));
        }
    }

    /** Sends a command to the running Minecraft server stdin. */
    public boolean sendServerCommand(String command) {
        MainOrchestrator orch = activeOrchestrator;
        if (orch == null || command == null || command.isBlank()) return false;
        String trimmed = command.trim();
        if (trimmed.equalsIgnoreCase("stop") || trimmed.equalsIgnoreCase("/stop")) {
            requestStopSession();
            return true;
        }
        return orch.sendServerCommand(trimmed);
    }

    public ServerProcessController activeServerController() {
        MainOrchestrator orch = activeOrchestrator;
        return orch != null ? orch.serverController() : null;
    }

    /** Runs `pull-mods` or `push-mods` asynchronously. */
    public void syncModsAsync(boolean push) {
        bg.submit(() -> {
            try {
                notifyStatus((push ? "Pubblicazione" : "Sincronizzazione") + " mod in corso...", false);
                MainOrchestrator.Prepared prep = MainOrchestrator.bootstrap(configPath, false);
                try (MainOrchestrator orch = new MainOrchestrator(prep.cfg(), false, prep.gitExecutable())) {
                    int code = push ? orch.pushMods() : orch.pullMods();
                    if (code == 0) {
                        notifyStatus(push ? "Mod pubblicate con successo!" : "Mod sincronizzate con successo!", false);
                    } else {
                        notifyStatus("Sincronizzazione mod terminata con codice " + code, true);
                    }
                }
            } catch (Exception e) {
                log.error("Mod sync error: {}", e.getMessage());
                notifyStatus("Errore sync mod: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    /** Forces deletion of a stale remote lock. */
    public void forceUnlockAsync() {
        bg.submit(() -> {
            try {
                notifyStatus("Rimozione forzata del lock remoto in corso...", false);
                MainOrchestrator.Prepared prep = MainOrchestrator.bootstrap(configPath, false);
                try (MainOrchestrator orch = new MainOrchestrator(prep.cfg(), false, prep.gitExecutable())) {
                    int code = orch.forceUnlock(true);
                    notifyStatus(code == 0 ? "Lock rimosso con successo!" : "Impossibile rimuovere il lock.", code != 0);
                }
            } catch (Exception e) {
                log.error("Force unlock error: {}", e.getMessage());
                notifyStatus("Errore force-unlock: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    /** Tests connectivity and read/write access to the configured Git repository or S3 bucket. */
    public void testStorageConnectionAsync(Config candidate, Consumer<String> onSuccess, Consumer<String> onError) {
        bg.submit(() -> {
            try {
                DependencyInstaller installer = new DependencyInstaller(baseDir());
                String gitExe = candidate.isGitBackend() ? installer.ensureGitInstalled() : "git";
                try (S3Storage storage = new S3Storage(candidate, gitExe)) {
                    storage.checkAccess();
                }
                onEdt(() -> onSuccess.accept("Connessione riuscita! Accesso al repository/bucket verificato."));
            } catch (Exception e) {
                onEdt(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /** Runs a pending Server Pack installer (Forge installer jar or startserver.bat). */
    public void runPendingServerInstallerAsync(Path installerFile) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                notifyStatus("Installazione Server Pack in corso (" + installerFile.getFileName() + ")...", false);
                DependencyInstaller inst = new DependencyInstaller(baseDir());
                int code = inst.runServerPackInstaller(cfg, installerFile);
                if (code == 0) {
                    configFixer.apply(LogAnalyzer.FixAction.DETECT_SERVER_COMMAND, cfg);
                    notifyStatus("Server Pack installato e comando di avvio aggiornato!", false);
                } else {
                    notifyStatus("Installazione Server Pack terminata con codice " + code, true);
                }
            } catch (Exception e) {
                log.error("Installer error: {}", e.getMessage());
                notifyStatus("Errore installazione Server Pack: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    /** Imports a CurseForge modpack or Server Pack .zip asynchronously. */
    public void importCurseForgeZipAsync(Path zipFile) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                notifyStatus("Importazione pacchetto CurseForge " + zipFile.getFileName() + " in corso...", false);
                DependencyInstaller inst = new DependencyInstaller(baseDir());
                inst.importCurseForgeZip(cfg, zipFile);
                notifyStatus("Pacchetto CurseForge importato con successo!", false);
            } catch (IOException e) {
                log.error("CurseForge import error: {}", e.getMessage());
                notifyStatus("Errore importazione CurseForge: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    public Path baseDir() {
        return configPath.getParent() != null ? configPath.getParent() : Path.of(".").toAbsolutePath().normalize();
    }

    public com.mcsync.world.WorldService createWorldService(Config cfg) throws IOException {
        Config c = cfg != null ? cfg : Config.load(configPath);
        DependencyInstaller inst = new DependencyInstaller(baseDir());
        String gitExe = c.isGitBackend() ? Optional.ofNullable(inst.findExistingGit()).orElse("git") : "git";
        return new com.mcsync.world.WorldService(
                configPath,
                c,
                gitExe,
                com.mcsync.world.SourceResolver.combined(c.curseforgeApiKey()),
                inst::findExistingPrismLauncher,
                msg -> {
                    notifyStatus(msg, false);
                    parseAndNotifyProgress(msg);
                });
    }

    private void parseAndNotifyProgress(String msg) {
        if (msg == null) return;
        int pctIdx = msg.indexOf("%");
        if (pctIdx > 0) {
            int start = pctIdx - 1;
            while (start >= 0 && Character.isDigit(msg.charAt(start))) start--;
            try {
                int pct = Integer.parseInt(msg.substring(start + 1, pctIdx));
                notifyProgress(Math.max(0, Math.min(100, pct)), msg, true);
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        notifyProgress(-1, msg, true);
    }

    public void notifyProgress(int percent, String detail, boolean active) {
        onEdt(() -> listeners.forEach(l -> l.onProgressUpdated(percent, detail, active)));
    }

    public void refreshWorldsAsync(Consumer<List<com.mcsync.world.WorldService.WorldStatus>> callback) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                List<com.mcsync.world.WorldService.WorldStatus> list = ws.listWorlds();
                onEdt(() -> callback.accept(list));
            } catch (Exception e) {
                log.debug("refreshWorldsAsync error: {}", e.getMessage());
                onEdt(() -> callback.accept(List.of()));
            }
        });
    }

    public void selectWorldAsync(String worldId, Runnable onDone) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                ws.setActiveWorld(worldId);
                notifyStatus("Mondo attivo impostato su '" + worldId + "'.", false);
            } catch (Exception e) {
                notifyStatus("Errore selezione mondo: " + e.getMessage(), true);
            } finally {
                if (onDone != null) onEdt(onDone);
                refreshDashboardAsync();
            }
        });
    }

    public void createWorldAsync(
            com.mcsync.world.CreateWorldRequest req, Path inviteOut, Runnable onSuccess) {
        bg.submit(() -> {
            try {
                notifyProgress(5, "Inizializzazione creazione mondo '" + req.displayName() + "'...", true);
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                com.mcsync.model.WorldProfile created = ws.create(req);
                if (req.exportInviteAfterCreate() && inviteOut != null) {
                    ws.exportInvite(created.id(), inviteOut);
                }
                notifyProgress(100, "Mondo '" + created.displayName() + "' creato con successo!", false);
                notifyStatus("Mondo '" + created.displayName() + "' creato con successo!", false);
                if (onSuccess != null) onEdt(onSuccess);
            } catch (Exception e) {
                log.error("Create world error: {}", e.getMessage(), e);
                notifyProgress(0, "Errore: " + e.getMessage(), false);
                notifyStatus("Errore creazione mondo: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    public void importInviteAsync(Path inviteFile, Runnable onSuccess) {
        bg.submit(() -> {
            try {
                notifyProgress(10, "Lettura invito " + inviteFile.getFileName() + "...", true);
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                com.mcsync.model.WorldProfile imported = ws.importInvite(inviteFile);
                notifyProgress(100, "Mondo '" + imported.displayName() + "' importato e pronto!", false);
                notifyStatus("Invito importato: mondo '" + imported.displayName() + "' pronto!", false);
                if (onSuccess != null) onEdt(onSuccess);
            } catch (Exception e) {
                log.error("Import invite error: {}", e.getMessage(), e);
                notifyProgress(0, "Errore: " + e.getMessage(), false);
                notifyStatus("Errore importazione invito: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    public void exportInviteAsync(String worldId, Path target) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                Path out = ws.exportInvite(worldId, target);
                notifyStatus("Invito esportato in: " + out, false);
            } catch (Exception e) {
                log.error("Export invite error: {}", e.getMessage(), e);
                notifyStatus("Errore esportazione invito: " + e.getMessage(), true);
            }
        });
    }

    public void updateModpackAsync(
            String worldId,
            com.mcsync.world.ModpackSource pack,
            com.mcsync.world.WorldService.UpdateOptions opt,
            com.mcsync.world.WorldService.Confirm confirm,
            Runnable onSuccess) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                long ver = ws.updateModpack(worldId, pack, opt, confirm);
                if (ver > 0) {
                    notifyStatus("Modpack aggiornato alla v" + ver + "!", false);
                    if (onSuccess != null) onEdt(onSuccess);
                }
            } catch (Exception e) {
                log.error("Update modpack error: {}", e.getMessage(), e);
                notifyStatus("Errore aggiornamento modpack: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    public void rollbackModpackAsync(String worldId, long targetVersion, Runnable onSuccess) {
        bg.submit(() -> {
            try {
                Config cfg = Config.load(configPath);
                com.mcsync.world.WorldService ws = createWorldService(cfg);
                long ver = ws.rollback(worldId, targetVersion);
                notifyStatus("Rollback modpack completato (nuova versione v" + ver + ")!", false);
                if (onSuccess != null) onEdt(onSuccess);
            } catch (Exception e) {
                log.error("Rollback modpack error: {}", e.getMessage(), e);
                notifyStatus("Errore rollback modpack: " + e.getMessage(), true);
            } finally {
                refreshDashboardAsync();
            }
        });
    }

    public void notifyStatus(String msg, boolean error) {
        onEdt(() -> listeners.forEach(l -> l.onStatusMessage(msg, error)));
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }
}
