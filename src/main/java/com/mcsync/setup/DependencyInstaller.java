package com.mcsync.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.config.Config;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import com.mcsync.util.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Automatic dependency bootstrapper &amp; CurseForge / Server Pack installer for Windows (and Linux/macOS).
 *
 * <ul>
 *     <li><b>Java 21+</b>: detects or downloads a portable Eclipse Temurin 21 JRE/JDK into {@code .tools/jdk-21}.</li>
 *     <li><b>Prism Launcher</b>: detects existing installation, installs via {@code winget} or downloads
 *     the official Windows Portable release from GitHub into {@code .tools/PrismLauncher}.</li>
 *     <li><b>Tailscale</b>: detects, installs via {@code winget} / official MSI with UAC elevation, starts
 *     the Windows service if stopped.</li>
 *     <li><b>Windows Firewall</b>: registers an inbound rule for the Minecraft server port (25565) on the
 *     Tailscale / private profile (elevating via UAC only if missing).</li>
 *     <li><b>CurseForge / Forge / NeoForge Server Packs</b>: strips blocking {@code pause} commands from
 *     {@code run.bat}/{@code start.bat} and auto-detects {@code @libraries/.../win_args.txt} or server JARs.</li>
 * </ul>
 */
public final class DependencyInstaller {

    private static final Logger log = LoggerFactory.getLogger(DependencyInstaller.class);

    private static final String PRISM_GITHUB_API =
            "https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest";
    private static final String TAILSCALE_WIN_MSI =
            "https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi";
    private static final String ADOPTIUM_WIN_JDK21 =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk";

    private final Path baseDir;
    private final Path toolsDir;
    private final HttpClient http;

    public DependencyInstaller(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.toolsDir = this.baseDir.resolve(".tools");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    // ==========================================================================================
    // Full auto-check & install entry point
    // ==========================================================================================

    /**
     * Ensures all runtime prerequisites are present on this PC and returns an updated {@link Config}
     * with resolved executable paths / server start commands if needed.
     */
    public Config ensureAll(Config cfg, boolean launchClient) {
        try {
            Files.createDirectories(toolsDir);
        } catch (IOException ignored) {
        }

        // 1. Ensure Git if using GIT backend
        if (cfg.isGitBackend()) {
            ensureGitInstalled();
        }

        // 2. Ensure Tailscale is installed & running
        ensureTailscaleInstalled();

        // 2. Ensure Windows Firewall rule for port 25565 exists
        if (FileUtils.IS_WINDOWS) {
            ensureWindowsFirewallRule(cfg.serverPort());
        }

        // 3. Ensure Prism Launcher (or CurseForge App) if client launch is enabled
        Config updated = cfg;
        if (launchClient && !cfg.clientLaunchCommand().isEmpty()) {
            updated = ensurePrismLauncher(updated);
        }

        // 4. Patch CurseForge / Forge / NeoForge server scripts & resolve server command
        updated = prepareServerPack(updated);

        return updated;
    }

    // ==========================================================================================
    // 1. Java 21 portable installer (callable from CLI `setup`)
    // ==========================================================================================

    public Path ensureJava21() throws IOException {
        Path localJava = FileUtils.IS_WINDOWS
                ? toolsDir.resolve("jdk-21/bin/java.exe")
                : toolsDir.resolve("jdk-21/bin/java");
        if (Files.isRegularFile(localJava)) {
            return localJava;
        }
        int currentMajor = Runtime.version().feature();
        if (currentMajor >= 21) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                Path sysJava = Path.of(javaHome, "bin", FileUtils.IS_WINDOWS ? "java.exe" : "java");
                if (Files.isRegularFile(sysJava)) return sysJava;
            }
        }
        if (!FileUtils.IS_WINDOWS) {
            log.info("Java {} detected.", currentMajor);
            return Path.of("java");
        }

        log.info("Downloading portable OpenJDK 21 (Eclipse Temurin) into {} ...", toolsDir.resolve("jdk-21"));
        Path zip = toolsDir.resolve("jdk21-win-x64.zip");
        downloadFile(ADOPTIUM_WIN_JDK21, zip);
        unzipStripTopLevel(zip, toolsDir.resolve("jdk-21"));
        Files.deleteIfExists(zip);
        log.info("Portable Java 21 installed at {}", localJava);
        return localJava;
    }

    // ==========================================================================================
    // 2. Prism Launcher detection & automatic portable installation
    // ==========================================================================================

    public Config ensurePrismLauncher(Config cfg) {
        List<String> cmd = cfg.clientLaunchCommand();
        if (cmd.isEmpty()) return cfg;

        String first = Config.expandHome(cmd.get(0));
        if (isExecutableAvailable(first)) {
            return cfg;
        }

        // Check known Prism Launcher installation locations on Windows / Linux / macOS
        Path found = findExistingPrismLauncher();
        if (found == null && FileUtils.IS_WINDOWS) {
            log.warn("Prism Launcher non trovato in '{}'. Installazione automatica in corso...", first);
            found = installPrismLauncherWindows();
        }

        if (found != null && Files.isRegularFile(found)) {
            log.info("Prism Launcher pronto: {}", found);
            List<String> newCmd = new ArrayList<>(cmd);
            newCmd.set(0, found.toString());
            return copyWithClientCommand(cfg, newCmd);
        } else {
            log.warn("Impossibile installare automaticamente Prism Launcher. Verifica il percorso in config.json.");
            return cfg;
        }
    }

    public Path findExistingPrismLauncher() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(toolsDir.resolve("PrismLauncher/prismlauncher.exe"));
        if (FileUtils.IS_WINDOWS) {
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            String userProfile = System.getenv("USERPROFILE");
            if (localAppData != null) {
                candidates.add(Path.of(localAppData, "Programs", "PrismLauncher", "prismlauncher.exe"));
            }
            if (programFiles != null) {
                candidates.add(Path.of(programFiles, "PrismLauncher", "prismlauncher.exe"));
            }
            if (userProfile != null) {
                candidates.add(Path.of(userProfile, "scoop", "apps", "prismlauncher", "current", "prismlauncher.exe"));
            }
        } else {
            candidates.add(Path.of("/usr/bin/prismlauncher"));
            candidates.add(Path.of("/usr/local/bin/prismlauncher"));
            candidates.add(Path.of("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher"));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private Path installPrismLauncherWindows() {
        Path portableDir = toolsDir.resolve("PrismLauncher");
        Path exe = portableDir.resolve("prismlauncher.exe");
        if (Files.isRegularFile(exe)) return exe;

        // First try portable ZIP from official GitHub Release (no admin rights needed, self-contained)
        try {
            log.info("Ricerca ultima versione di Prism Launcher (Windows Portable) su GitHub...");
            HttpRequest req = HttpRequest.newBuilder(URI.create(PRISM_GITHUB_API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "mc-sync-launcher")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = Json.mapper().readTree(resp.body());
                JsonNode assets = root.path("assets");
                String downloadUrl = null;
                for (JsonNode asset : assets) {
                    String name = asset.path("name").asText("");
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("windows") && lower.contains("portable") && lower.endsWith(".zip")
                            && !lower.contains("arm64") && !lower.contains("debug")) {
                        downloadUrl = asset.path("browser_download_url").asText(null);
                        if (lower.contains("msvc") || lower.contains("mingw")) break;
                    }
                }
                if (downloadUrl != null) {
                    Path zip = toolsDir.resolve("PrismLauncher-Portable.zip");
                    log.info("Download Prism Launcher Portable da {} ...", downloadUrl);
                    downloadFile(downloadUrl, zip);
                    Files.createDirectories(portableDir);
                    unzip(zip, portableDir);
                    Files.deleteIfExists(zip);
                    // Locate prismlauncher.exe inside portableDir
                    try (Stream<Path> s = Files.walk(portableDir, 3)) {
                        Optional<Path> found = s.filter(p -> p.getFileName().toString().equalsIgnoreCase("prismlauncher.exe"))
                                .findFirst();
                        if (found.isPresent()) {
                            return found.get();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Download portable di Prism Launcher fallito ({}), provo tramite winget...", e.getMessage());
        }

        // Fallback: winget install PrismLauncher.PrismLauncher
        if (runCommandQuietly(List.of("winget", "install", "--id", "PrismLauncher.PrismLauncher",
                "-e", "--silent", "--accept-package-agreements", "--accept-source-agreements"), 180)) {
            return findExistingPrismLauncher();
        }
        return null;
    }

    // ==========================================================================================
    // 3. Tailscale detection, installation & service start
    // ==========================================================================================

    public void ensureTailscaleInstalled() {
        String ip = NetUtils.detectTailscaleIp();
        if (ip != null) return;

        if (!FileUtils.IS_WINDOWS) {
            log.warn("Tailscale non rilevato. Su Linux/macOS installalo da https://tailscale.com/download");
            return;
        }

        Path tailscaleExe = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                "Tailscale", "tailscale.exe");

        if (!Files.isRegularFile(tailscaleExe)) {
            log.warn("Tailscale non trovato su questo PC. Avvio installazione automatica...");
            boolean installed = runCommandQuietly(List.of("winget", "install", "--id", "tailscale.tailscale",
                    "-e", "--silent", "--accept-package-agreements", "--accept-source-agreements"), 180);
            if (!installed && !Files.isRegularFile(tailscaleExe)) {
                try {
                    Path msi = toolsDir.resolve("tailscale-setup.msi");
                    log.info("Download installer ufficiale Tailscale MSI...");
                    downloadFile(TAILSCALE_WIN_MSI, msi);
                    log.info("Esecuzione installer Tailscale (accetta il prompt UAC di Windows se richiesto)...");
                    runElevatedWindows("msiexec.exe", "/i \"" + msi.toAbsolutePath() + "\" /passive /norestart");
                } catch (Exception e) {
                    log.error("Installazione automatica di Tailscale fallita: {}", e.getMessage());
                }
            }
        }

        if (Files.isRegularFile(tailscaleExe) && NetUtils.detectTailscaleIp() == null) {
            log.info("Avvio del servizio Tailscale e apertura login...");
            runElevatedWindows("net", "start Tailscale");
            runCommandQuietly(List.of(tailscaleExe.toString(), "up"), 30);
        }
    }

    // ==========================================================================================
    // 4. Windows Firewall automatic rule (UAC elevated only if missing)
    // ==========================================================================================

    public void ensureWindowsFirewallRule(int port) {
        if (!FileUtils.IS_WINDOWS) return;
        String ruleName = "mc-sync-launcher (TCP " + port + ")";
        if (runCommandQuietly(List.of("netsh", "advfirewall", "firewall", "show", "rule", "name=" + ruleName), 10)) {
            return;
        }
        log.info("Configurazione regola Windows Firewall per la porta {} (potrebbe richiedere conferma UAC)...", port);
        String args = "advfirewall firewall add rule name=\"" + ruleName
                + "\" dir=in action=allow protocol=TCP localport=" + port;
        runElevatedWindows("netsh", args);
    }

    // ==========================================================================================
    // 5. CurseForge / Forge / NeoForge / Fabric Server Pack helper & Import
    // ==========================================================================================

    /**
     * Adapts CurseForge Server Packs so they work unattended:
     * <ul>
     *     <li>Removes trailing {@code pause} commands from {@code run.bat} / {@code start.bat} so
     *     the server process can exit cleanly on shutdown without waiting for a key press.</li>
     *     <li>If the configured JAR in {@code serverStartCommand} does not exist, automatically
     *     discovers Forge/NeoForge {@code @libraries/.../win_args.txt} or Fabric/Quilt/Forge server
     *     JARs in {@code serverDirectory}.</li>
     *     <li>Ensures {@codejava} in {@code serverStartCommand} uses our Java 21+ binary if the
     *     system {@code PATH} points to an older Java.</li>
     * </ul>
     */
    public Config prepareServerPack(Config cfg) {
        Path serverDir = cfg.serverDir();
        if (!Files.isDirectory(serverDir)) return cfg;

        // Sanitize run.bat / start.bat / ServerStart.bat in CurseForge server packs (strip "pause")
        for (String scriptName : List.of("run.bat", "start.bat", "ServerStart.bat", "startserver.bat")) {
            Path bat = serverDir.resolve(scriptName);
            if (Files.isRegularFile(bat)) {
                sanitizeBatchScript(bat);
            }
        }

        // Resolve a Java 21+ runtime for the server: run.bat/run.sh call the bare "java" of the PATH,
        // which on many Windows PCs is Java 8 ("Could not find or load main class @user_jvm_args.txt").
        Config updated = cfg;
        Path java21 = null;
        try {
            java21 = ensureJava21();
        } catch (IOException e) {
            log.warn("Java 21 non disponibile per il server: {}", e.getMessage());
        }
        if (java21 != null && Files.isRegularFile(java21) && cfg.serverJavaHome().isBlank()) {
            Path home = java21.toAbsolutePath().getParent().getParent();
            updated = updated.with(java.util.Map.of("serverJavaHome", home.toString()));
        }

        // Check if the configured -jar <file> exists; if not, auto-detect the server pack layout (@libraries/... or runnable jar)
        List<String> origCmd = new ArrayList<>(cfg.serverStartCommand());
        int origJarIdx = origCmd.indexOf("-jar");
        if (origJarIdx >= 0 && origJarIdx + 1 < origCmd.size()) {
            Path jarFile = serverDir.resolve(origCmd.get(origJarIdx + 1));
            if (!Files.isRegularFile(jarFile)) {
                String javaBin = (java21 != null && Files.isRegularFile(java21)) ? java21.toString() : origCmd.get(0);
                List<String> detected = detectServerStartCommand(serverDir, javaBin);
                if (detected != null) {
                    log.info("Comando server rilevato automaticamente per il Server Pack: {}", detected);
                    return copyWithServerCommand(updated, detected);
                }
            }
        }

        // Pre-flight validation / auto-detection of the start command (run.bat, Fabric jar, ...)
        List<String> repaired = ServerProfileDetector.repairCommand(cfg.serverStartCommand(), serverDir);
        if (repaired.isEmpty()) return updated;
        List<String> cmd = new ArrayList<>(repaired);
        if (!cmd.equals(cfg.serverStartCommand())) {
            log.info("Comando server corretto/rilevato automaticamente: {}", cmd);
        }

        // Replace "java" with the portable/verified Java 21
        if ((cmd.get(0).equalsIgnoreCase("java") || cmd.get(0).equalsIgnoreCase("java.exe"))
                && java21 != null && Files.isRegularFile(java21)) {
            cmd.set(0, java21.toString());
        }

        return copyWithServerCommand(updated, cmd);
    }

    /** Runs a pending Forge installer / server pack install script in {@code serverDirectory}. */
    public int runServerPackInstaller(Config cfg, Path installer) throws IOException, InterruptedException {
        Path java = ensureJava21();
        List<String> cmd = ServerProfileDetector.installerCommand(installer,
                java != null ? java.toString() : "java");
        log.info("Esecuzione installazione Server Pack: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cfg.serverDir().toFile()).redirectErrorStream(true);
        if (java != null) com.mcsync.util.ProcessUtils.applyJavaHome(pb, java.toAbsolutePath().getParent().getParent());
        Process p = pb.start();
        p.getOutputStream().close();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),
                com.mcsync.util.ProcessUtils.childCharset()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println("[installer] " + line);
        }
        int code = p.waitFor();
        log.info("Installazione terminata con codice {}", code);
        return code;
    }

    /**
     * Installs/extracts a CurseForge Server Pack `.zip` (or imports a CurseForge client modpack `.zip`
     * into Prism Launcher).
     */
    public void importCurseForgeZip(Config cfg, Path zipFile) throws IOException {
        if (!Files.isRegularFile(zipFile)) {
            throw new IOException("File zip non trovato: " + zipFile);
        }
        boolean isClientModpack = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.equals("manifest.json") || name.startsWith("overrides/")) {
                    isClientModpack = true;
                    break;
                }
            }
        }

        if (isClientModpack) {
            log.info("Rilevato modpack client CurseForge: {}", zipFile.getFileName());
            // Extract overrides/ (mods, config, defaultconfigs, kubejs, scripts...) to serverDir as well
            extractCurseForgeOverrides(zipFile, cfg.serverDir());
            Path prism = findExistingPrismLauncher();
            if (prism == null && FileUtils.IS_WINDOWS) prism = installPrismLauncherWindows();
            if (prism != null) {
                log.info("Importazione del modpack CurseForge in Prism Launcher...");
                new ProcessBuilder(prism.toString(), "--import", zipFile.toAbsolutePath().toString()).start();
            } else {
                log.info("Overrides estratti in {}. Importa lo zip nel tuo launcher client.", cfg.serverDir());
            }
        } else {
            log.info("Estrazione Server Pack CurseForge in {} ...", cfg.serverDir());
            Files.createDirectories(cfg.serverDir());
            unzipStripTopLevelIfSingleFolder(zipFile, cfg.serverDir());
            prepareServerPack(cfg);
            log.info("Server Pack installato con successo in {}", cfg.serverDir());
        }
    }

    private void extractCurseForgeOverrides(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith("overrides/") || entry.isDirectory()) continue;
                String rel = name.substring("overrides/".length());
                if (rel.isEmpty()) continue;
                Path out = targetDir.resolve(rel).normalize();
                if (!out.startsWith(targetDir)) continue;
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zis.transferTo(os);
                }
            }
        }
    }

    private static List<String> detectServerStartCommand(Path serverDir, String javaBin) {
        // 1. Modern Forge / NeoForge (@user_jvm_args.txt @libraries/.../win_args.txt or unix_args.txt)
        String argsFileName = FileUtils.IS_WINDOWS ? "win_args.txt" : "unix_args.txt";
        Path libraries = serverDir.resolve("libraries");
        if (Files.isDirectory(libraries)) {
            try (Stream<Path> s = Files.walk(libraries, 8)) {
                Optional<Path> argsFile = s.filter(p -> p.getFileName().toString().equals(argsFileName)).findFirst();
                if (argsFile.isPresent()) {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(javaBin);
                    if (Files.isRegularFile(serverDir.resolve("user_jvm_args.txt"))) {
                        cmd.add("@user_jvm_args.txt");
                    } else {
                        cmd.add("-Xms4G");
                        cmd.add("-Xmx6G");
                    }
                    cmd.add("@" + FileUtils.toPortable(serverDir.relativize(argsFile.get())));
                    cmd.add("nogui");
                    return cmd;
                }
            } catch (IOException ignored) {
            }
        }

        // 2. Fabric / Quilt / Forge / Paper runnable server JARs
        try (Stream<Path> s = Files.list(serverDir)) {
            List<Path> jars = s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().toLowerCase(Locale.ROOT).contains("installer"))
                    .sorted(Comparator.comparingInt(p -> jarPriority(p.getFileName().toString())))
                    .toList();
            if (!jars.isEmpty()) {
                return List.of(javaBin, "-Xms4G", "-Xmx6G", "-XX:+UseG1GC", "-jar",
                        jars.get(0).getFileName().toString(), "nogui");
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static int jarPriority(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        if (l.contains("fabric-server-launch") || l.contains("fabric-server")) return 0;
        if (l.contains("neoforge") || l.contains("forge")) return 1;
        if (l.contains("quilt-server")) return 2;
        if (l.contains("server")) return 3;
        return 4;
    }

    private static void sanitizeBatchScript(Path bat) {
        try {
            List<String> lines = Files.readAllLines(bat, StandardCharsets.ISO_8859_1);
            boolean modified = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim().toLowerCase(Locale.ROOT);
                if (trimmed.equals("pause") || trimmed.startsWith("pause ")) {
                    lines.set(i, "REM pause removed by mc-sync-launcher");
                    modified = true;
                }
            }
            if (modified) {
                Files.write(bat, lines, StandardCharsets.ISO_8859_1);
                log.info("Rimosso 'pause' bloccante da {}", bat.getFileName());
            }
        } catch (IOException ignored) {
        }
    }

    // ==========================================================================================
    // Download, ZIP & Windows UAC elevation utilities
    // ==========================================================================================

    public void downloadFile(String url, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "mc-sync-launcher/1.0")
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
            }
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long readTotal = 0;
                int lastPct = -1;
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    readTotal += r;
                    if (total > 0) {
                        int pct = (int) ((readTotal * 100) / total);
                        if (pct >= lastPct + 20) {
                            lastPct = pct;
                            log.info("  download {}% ({})", pct, FileUtils.humanSize(readTotal));
                        }
                    }
                }
            }
            FileUtils.moveWithRetry(tmp, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void unzip(Path zip, Path targetDir) throws IOException {
        Path root = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = root.resolve(e.getName().replace('\\', '/')).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe zip entry: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        zis.transferTo(os);
                    }
                }
            }
        }
    }

    private static void unzipStripTopLevel(Path zip, Path targetDir) throws IOException {
        Path tmp = targetDir.resolveSibling(targetDir.getFileName() + "_extract");
        FileUtils.deleteRecursively(tmp);
        unzip(zip, tmp);
        try (Stream<Path> s = Files.list(tmp)) {
            List<Path> children = s.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                FileUtils.deleteRecursively(targetDir);
                FileUtils.moveWithRetry(children.get(0), targetDir);
                FileUtils.deleteRecursively(tmp);
                return;
            }
        }
        FileUtils.deleteRecursively(targetDir);
        FileUtils.moveWithRetry(tmp, targetDir);
    }

    private static void unzipStripTopLevelIfSingleFolder(Path zip, Path targetDir) throws IOException {
        Path tmp = targetDir.resolve(".mcsync_import_tmp");
        FileUtils.deleteRecursively(tmp);
        unzip(zip, tmp);
        Path sourceRoot = tmp;
        try (Stream<Path> s = Files.list(tmp)) {
            List<Path> children = s.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                sourceRoot = children.get(0);
            }
        }
        final Path root = sourceRoot;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                if (p.equals(root)) continue;
                Path dest = targetDir.resolve(root.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        FileUtils.deleteRecursively(tmp);
    }

    private static boolean isExecutableAvailable(String exe) {
        Path p = Path.of(exe);
        if (p.isAbsolute() || exe.contains("/") || exe.contains("\\")) {
            return Files.isRegularFile(p);
        }
        String checkCmd = FileUtils.IS_WINDOWS ? "where" : "which";
        return runCommandQuietly(List.of(checkCmd, exe), 5);
    }

    private static boolean runCommandQuietly(List<String> cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(timeoutSec, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Executes a command with Windows UAC Administrator elevation (`RunAs`). */
    private static boolean runElevatedWindows(String file, String args) {
        if (!FileUtils.IS_WINDOWS) return false;
        String psCmd = "Start-Process -FilePath '" + file.replace("'", "''")
                + "' -ArgumentList '" + args.replace("'", "''") + "' -Verb RunAs -Wait";
        return runCommandQuietly(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCmd), 300);
    }

    // ==========================================================================================
    // 1b. Git detection & automatic PortableGit / Winget installation on Windows
    // ==========================================================================================

    public String ensureGitInstalled() {
        String existing = findExistingGit();
        if (existing != null) return existing;

        if (!FileUtils.IS_WINDOWS) {
            log.warn("Git non trovato sul sistema. Installalo con il tuo package manager (es. apt/dnf/brew install git).");
            return "git";
        }

        log.warn("Git non trovato su questo PC Windows. Avvio installazione automatica di Git...");
        // Try MinGit (official portable zip from Git for Windows GitHub release)
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/git-for-windows/git/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "mc-sync-launcher")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode assets = Json.mapper().readTree(resp.body()).path("assets");
                String mingitUrl = null;
                for (JsonNode asset : assets) {
                    String name = asset.path("name").asText("");
                    if (name.startsWith("MinGit-") && name.endsWith("-64-bit.zip") && !name.contains("busybox")) {
                        mingitUrl = asset.path("browser_download_url").asText(null);
                        break;
                    }
                }
                if (mingitUrl != null) {
                    Path zip = toolsDir.resolve("mingit.zip");
                    Path gitDir = toolsDir.resolve("git");
                    log.info("Download MinGit Portable (64-bit) da {} ...", mingitUrl);
                    downloadFile(mingitUrl, zip);
                    Files.createDirectories(gitDir);
                    unzip(zip, gitDir);
                    Files.deleteIfExists(zip);
                    Path gitExe = gitDir.resolve("cmd/git.exe");
                    if (Files.isRegularFile(gitExe)) {
                        log.info("Git Portable installato con successo in {}", gitExe);
                        return gitExe.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Download MinGit fallito ({}), provo installazione tramite winget...", e.getMessage());
        }

        if (runCommandQuietly(List.of("winget", "install", "--id", "Git.Git",
                "-e", "--silent", "--accept-package-agreements", "--accept-source-agreements"), 240)) {
            String after = findExistingGit();
            if (after != null) return after;
        }
        return "git";
    }

    public String findExistingGit() {
        Path bundled = toolsDir.resolve("git/cmd/git.exe");
        if (Files.isRegularFile(bundled)) return bundled.toString();
        if (isExecutableAvailable(FileUtils.IS_WINDOWS ? "git.exe" : "git")) {
            return "git";
        }
        if (FileUtils.IS_WINDOWS) {
            for (String pf : List.of(
                    System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                    System.getenv().getOrDefault("LOCALAPPDATA", "") + "\\Programs")) {
                Path p = Path.of(pf, "Git", "cmd", "git.exe");
                if (Files.isRegularFile(p)) return p.toString();
            }
        }
        return null;
    }

    private static Config copyWithClientCommand(Config c, List<String> clientCmd) {
        return c.withClientLaunchCommand(clientCmd);
    }

    private static Config copyWithServerCommand(Config c, List<String> serverCmd) {
        return c.withServerStartCommand(serverCmd);
    }
}
