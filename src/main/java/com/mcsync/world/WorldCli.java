package com.mcsync.world;

import com.mcsync.MainOrchestrator;
import com.mcsync.config.Config;
import com.mcsync.model.WorldProfile;
import com.mcsync.setup.DependencyInstaller;
import com.mcsync.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CLI dispatcher for multi-world commands (§6, §8, §9, §11 of the plan):
 * <ul>
 *     <li>{@code world create|list|export|import|select|remove}</li>
 *     <li>{@code join <worldId>}</li>
 *     <li>{@code join-file <invito.mcsync>}</li>
 *     <li>{@code modpack update|rollback|versions}</li>
 *     <li>{@code migrate} / {@code migrate-rollback}</li>
 * </ul>
 */
public final class WorldCli {

    private static final Logger log = LoggerFactory.getLogger(WorldCli.class);

    private WorldCli() {
    }

    public static WorldService newService(Path cfgFile, Config cfg, DependencyInstaller installer) {
        String gitExe = cfg.isGitBackend() ? installer.ensureGitInstalled() : "git";
        return new WorldService(
                cfgFile,
                cfg,
                gitExe,
                SourceResolver.combined(cfg.curseforgeApiKey()),
                installer::findExistingPrismLauncher,
                msg -> System.out.println("[world] " + msg));
    }

    public static int handleWorldCommand(
            Path cfgFile, Config cfg, DependencyInstaller installer, List<String> args, boolean assumeYes) throws IOException {
        String sub = args.isEmpty() ? "list" : args.remove(0).toLowerCase(Locale.ROOT);
        WorldService svc = newService(cfgFile, cfg, installer);
        return switch (sub) {
            case "list", "ls" -> {
                List<WorldService.WorldStatus> list = svc.listWorlds();
                if (list.isEmpty()) {
                    System.out.println("Nessun mondo registrato. Usa 'world create' o 'world import <invito.mcsync>'.");
                } else {
                    String active = svc.config().activeWorldId();
                    for (WorldService.WorldStatus ws : list) {
                        WorldProfile p = ws.profile();
                        String mark = p.id().equals(active) ? "* " : "  ";
                        System.out.printf("%s%-18s %-28s [%s] (modpack v%d)%n",
                                mark, p.id(), p.displayName(), ws.label(), ws.remoteModpackVersion());
                    }
                }
                yield 0;
            }
            case "create" -> {
                if (args.isEmpty()) {
                    log.error("Uso: launch.bat world create <worldId> --client-pack <pack> [--server-pack <srv.zip>] [--name <nome>]");
                    yield 2;
                }
                String worldId = args.remove(0);
                String name = takeOpt(args, "--name", worldId);
                String clientPackStr = takeOpt(args, "--client-pack", null);
                if (clientPackStr == null && !args.isEmpty() && !args.get(0).startsWith("-")) {
                    clientPackStr = args.remove(0);
                }
                if (clientPackStr == null) {
                    log.error("Specificare --client-pack <percorso-zip-mrpack-istanza-o-cartella>");
                    yield 2;
                }
                String serverPackStr = takeOpt(args, "--server-pack", null);
                int ramMin = Integer.parseInt(takeOpt(args, "--ram-min", "4096"));
                int ramMax = Integer.parseInt(takeOpt(args, "--ram-max", "6144"));
                int port = Integer.parseInt(takeOpt(args, "--port", String.valueOf(cfg.serverPort())));
                String exportPath = takeOpt(args, "--export-invite", null);
                boolean exportFlag = args.remove("--export-invite") || exportPath != null;

                ModpackSource clientPack = ZipClassifier.toModpackSource(Path.of(clientPackStr));
                ModpackSource serverPack = serverPackStr != null ? ZipClassifier.toModpackSource(Path.of(serverPackStr)) : null;

                CreateWorldRequest req = new CreateWorldRequest(
                        worldId, name, clientPack, serverPack, ramMin, ramMax, port,
                        cfg.whitelistPlayers(), exportFlag);
                WorldProfile created = svc.create(req);
                if (exportFlag) {
                    Path out = exportPath != null ? Path.of(exportPath) : Path.of(created.id() + ".mcsync");
                    svc.exportInvite(created.id(), out);
                }
                yield 0;
            }
            case "export", "export-invite" -> {
                String worldId = !args.isEmpty() ? args.remove(0) : svc.config().activeWorldId();
                if (worldId == null || worldId.isBlank()) {
                    log.error("Uso: launch.bat world export <worldId> [file.mcsync]");
                    yield 2;
                }
                Path out = !args.isEmpty() ? Path.of(args.remove(0)) : Path.of(worldId + ".mcsync");
                svc.exportInvite(worldId, out);
                yield 0;
            }
            case "import", "import-invite" -> {
                if (args.isEmpty()) {
                    log.error("Uso: launch.bat world import <invito.mcsync>");
                    yield 2;
                }
                svc.importInvite(Path.of(args.remove(0)));
                yield 0;
            }
            case "select", "use" -> {
                if (args.isEmpty()) {
                    log.error("Uso: launch.bat world select <worldId>");
                    yield 2;
                }
                String id = args.remove(0);
                svc.setActiveWorld(id);
                System.out.println("Mondo attivo impostato su: " + id);
                yield 0;
            }
            case "remove", "rm" -> {
                if (args.isEmpty()) {
                    log.error("Uso: launch.bat world remove <worldId> [--delete-files]");
                    yield 2;
                }
                String id = args.remove(0);
                boolean del = args.remove("--delete-files");
                svc.removeLocal(id, del);
                System.out.println("Mondo '" + id + "' rimosso da questo PC.");
                yield 0;
            }
            default -> {
                log.error("Sottocomando 'world {}' sconosciuto (attesi: create, list, export, import, select, remove)", sub);
                yield 2;
            }
        };
    }

    public static int handleModpackCommand(
            Path cfgFile, Config cfg, DependencyInstaller installer, List<String> args, boolean assumeYes) throws IOException {
        if (args.isEmpty()) {
            log.error("Uso: launch.bat modpack <update|rollback|versions> [worldId] ...");
            return 2;
        }
        String sub = args.remove(0).toLowerCase(Locale.ROOT);
        WorldService svc = newService(cfgFile, cfg, installer);
        return switch (sub) {
            case "update" -> {
                String packStr = takeOpt(args, "--client-pack", null);
                String srvPackStr = takeOpt(args, "--server-pack", null);
                String worldId = svc.config().activeWorldId();
                if (!args.isEmpty() && svc.config().findWorld(args.get(0)).isPresent()) {
                    worldId = args.remove(0);
                }
                if (packStr == null && !args.isEmpty()) {
                    packStr = args.remove(0);
                }
                if (worldId == null || worldId.isBlank()) {
                    log.error("Specifica il mondo da aggiornare (o imposta activeWorldId).");
                    yield 2;
                }
                ModpackSource pack;
                if (packStr != null) {
                    pack = ZipClassifier.toModpackSource(Path.of(packStr));
                } else {
                    WorldProfile wp = svc.config().findWorld(worldId).orElseThrow();
                    if (wp.clientInstanceDirectory().isBlank()) {
                        log.error("Specificare --client-pack <percorso>");
                        yield 2;
                    }
                    Path instDir = Path.of(Config.expandHome(wp.clientInstanceDirectory())).getParent();
                    pack = Files.isRegularFile(instDir.resolve("mmc-pack.json"))
                            ? new ModpackSource.PrismInstance(instDir)
                            : new ModpackSource.Folder(Path.of(Config.expandHome(wp.clientInstanceDirectory())));
                }
                ModpackSource srvPack = srvPackStr != null ? ZipClassifier.toModpackSource(Path.of(srvPackStr)) : null;
                WorldService.UpdateOptions opt = new WorldService.UpdateOptions(srvPack, true, List.of(), List.of());
                long v = svc.updateModpack(worldId, pack, opt, diff -> {
                    if (assumeYes) return true;
                    System.out.print("Confermi la pubblicazione del nuovo modpack? [y/N]: ");
                    try {
                        String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
                        return line != null && (line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes")
                                || line.trim().equalsIgnoreCase("s") || line.trim().equalsIgnoreCase("si"));
                    } catch (IOException e) {
                        return false;
                    }
                });
                yield v > 0 ? 0 : 1;
            }
            case "rollback" -> {
                String worldId = svc.config().activeWorldId();
                if (!args.isEmpty() && svc.config().findWorld(args.get(0)).isPresent()) {
                    worldId = args.remove(0);
                }
                if (worldId == null || worldId.isBlank()) {
                    log.error("Uso: launch.bat modpack rollback <worldId> [versione]");
                    yield 2;
                }
                List<Long> versions = svc.modpackVersions(worldId);
                long target;
                if (!args.isEmpty()) {
                    String raw = args.remove(0).toLowerCase(Locale.ROOT);
                    if (raw.startsWith("v")) raw = raw.substring(1);
                    target = Long.parseLong(raw);
                } else if (versions.size() >= 2) {
                    target = versions.get(versions.size() - 2);
                } else {
                    log.error("Nessuna versione precedente disponibile per il rollback di '{}'", worldId);
                    yield 1;
                }
                svc.rollback(worldId, target);
                yield 0;
            }
            case "versions", "history" -> {
                String worldId = !args.isEmpty() ? args.remove(0) : svc.config().activeWorldId();
                List<Long> versions = svc.modpackVersions(worldId);
                System.out.println("Versioni modpack per '" + worldId + "': " + versions);
                yield 0;
            }
            default -> {
                log.error("Sottocomando 'modpack {}' sconosciuto (attesi: update, rollback, versions)", sub);
                yield 2;
            }
        };
    }

    public static int handleMigrateCommand(Path cfgFile, Config cfg, DependencyInstaller installer, boolean rollback) throws IOException {
        String gitExe = cfg.isGitBackend() ? installer.ensureGitInstalled() : "git";
        try (S3Storage root = new S3Storage(cfg.atRepoRoot(), gitExe)) {
            if (rollback) {
                V11Migrator.rollback(cfgFile, root);
                System.out.println("Rollback migrazione v1.1 completato.");
            } else {
                V11Migrator.MigrationResult res = V11Migrator.migrateIfNeeded(cfgFile, cfg, root);
                System.out.println("Migrazione completata: config=" + res.configMigrated()
                        + ", storage=" + res.storageMigrated() + " (" + res.movedKeys() + " chiavi spostate).");
            }
        }
        return 0;
    }

    private static String takeOpt(List<String> args, String flag, String def) {
        int i = args.indexOf(flag);
        if (i >= 0 && i + 1 < args.size()) {
            String val = args.get(i + 1);
            args.remove(i + 1);
            args.remove(i);
            return val;
        }
        return def;
    }
}
