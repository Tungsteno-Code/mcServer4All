package com.mcsync.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcsync.model.Invite;
import com.mcsync.model.WorldProfile;
import com.mcsync.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Launcher configuration, deserialised from {@code config.json}.
 *
 * <p>Supports two remote storage backends ({@code storageBackend}):</p>
 * <ul>
 *     <li><b>{@code GIT}</b> (recommended): uses a private GitHub / GitLab repository
 *     ({@code gitRemoteUrl}, {@code gitToken}, {@code gitBranch}) with per-file differential sync
 *     and single-commit history.</li>
 *     <li><b>{@code S3}</b>: uses an S3-compatible bucket (Cloudflare R2, MinIO, AWS S3).</li>
 * </ul>
 *
 * <p>Secrets can be supplied through environment variables instead of the JSON file:
 * {@code MCSYNC_GIT_TOKEN}, {@code MCSYNC_S3_ACCESS_KEY}, {@code MCSYNC_S3_SECRET_KEY},
 * {@code MCSYNC_DISCORD_WEBHOOK}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Config(
        // ---- Core identification & networking -----------------------------------------------
        String playerId,
        String localTailscaleIp,

        // ---- Storage Backend selection ("GIT" or "S3") --------------------------------------
        String storageBackend,
        String gitRemoteUrl,
        String gitToken,
        String gitBranch,

        // ---- S3 configuration (only required when storageBackend = "S3") --------------------
        String s3Endpoint,
        String s3Region,
        String s3Bucket,
        String s3AccessKey,
        String s3SecretKey,

        // ---- Server & Client configuration --------------------------------------------------
        String serverDirectory,
        List<String> serverStartCommand,
        String worldFolderName,
        List<String> clientLaunchCommand,
        String discordWebhookUrl,
        Integer heartbeatIntervalSeconds,
        Integer lockTimeoutSeconds,
        Integer maxBackupsToKeep,

        // ---- Extensions (all optional) ------------------------------------------------------
        Integer serverPort,
        Boolean s3PathStyle,
        String s3KeyPrefix,
        List<String> whitelistPlayers,
        Boolean acceptEula,
        Integer idleShutdownSeconds,
        Integer playerPollIntervalSeconds,
        Integer initialJoinGraceSeconds,
        Integer serverStartTimeoutSeconds,
        Integer stopTimeoutSeconds,
        Integer hostWaitTimeoutSeconds,
        String localClientAddress,
        String modSyncMode,
        String clientInstanceDirectory,
        List<String> modSyncExtensions,
        List<String> modSyncExcludes,
        Boolean echoServerOutput,

        // ---- v1.1 additions (all optional) --------------------------------------------------
        String clientSyncScope,
        Boolean serverBindAll,
        String serverJavaHome,

        // ---- v2.0 additions: multi-world (all optional) -------------------------------------
        List<WorldProfile> worlds,
        String activeWorldId,
        String serversRoot,
        String curseforgeApiKey,
        String cacheDirectory
) {

    public static final List<String> DEFAULT_MOD_EXTENSIONS =
            List.of(".jar", ".json", ".toml", ".cfg", ".snbt");

    public Config {
        localTailscaleIp = blankToDefault(localTailscaleIp, "auto");
        gitRemoteUrl = gitRemoteUrl == null ? "" : gitRemoteUrl.trim();
        gitToken = firstNonBlank(System.getenv("MCSYNC_GIT_TOKEN"), gitToken, "");
        gitBranch = blankToDefault(gitBranch, "mcsync-state");

        // Auto-detect backend: if storageBackend is explicitly set use it; otherwise GIT if gitRemoteUrl is set, else S3
        if (isBlank(storageBackend)) {
            storageBackend = !isBlank(gitRemoteUrl) ? "GIT" : "S3";
        } else {
            storageBackend = storageBackend.trim().toUpperCase(Locale.ROOT);
        }

        s3Region = blankToDefault(s3Region, "auto");
        worldFolderName = blankToDefault(worldFolderName, "world");
        discordWebhookUrl = firstNonBlank(System.getenv("MCSYNC_DISCORD_WEBHOOK"), discordWebhookUrl, "");
        s3AccessKey = firstNonBlank(System.getenv("MCSYNC_S3_ACCESS_KEY"), s3AccessKey, "");
        s3SecretKey = firstNonBlank(System.getenv("MCSYNC_S3_SECRET_KEY"), s3SecretKey, "");
        serverStartCommand = serverStartCommand == null ? List.of() : List.copyOf(serverStartCommand);
        clientLaunchCommand = clientLaunchCommand == null ? List.of() : List.copyOf(clientLaunchCommand);
        heartbeatIntervalSeconds = positiveOrDefault(heartbeatIntervalSeconds, 30);
        lockTimeoutSeconds = positiveOrDefault(lockTimeoutSeconds, 120);
        maxBackupsToKeep = positiveOrDefault(maxBackupsToKeep, 5);

        serverPort = positiveOrDefault(serverPort, 25565);
        s3PathStyle = s3PathStyle == null ? Boolean.TRUE : s3PathStyle;
        s3KeyPrefix = normalisePrefix(s3KeyPrefix);
        whitelistPlayers = whitelistPlayers == null ? List.of() : List.copyOf(whitelistPlayers);
        acceptEula = acceptEula != null && acceptEula;
        idleShutdownSeconds = positiveOrDefault(idleShutdownSeconds, 60);
        playerPollIntervalSeconds = positiveOrDefault(playerPollIntervalSeconds, 15);
        initialJoinGraceSeconds = positiveOrDefault(initialJoinGraceSeconds, 900);
        serverStartTimeoutSeconds = positiveOrDefault(serverStartTimeoutSeconds, 900);
        stopTimeoutSeconds = positiveOrDefault(stopTimeoutSeconds, 60);
        hostWaitTimeoutSeconds = positiveOrDefault(hostWaitTimeoutSeconds, 900);
        localClientAddress = localClientAddress == null ? "" : localClientAddress.trim();
        modSyncMode = blankToDefault(modSyncMode, "DIFFERENTIAL").toUpperCase(Locale.ROOT);
        clientInstanceDirectory = clientInstanceDirectory == null ? "" : clientInstanceDirectory.trim();
        modSyncExtensions = modSyncExtensions == null || modSyncExtensions.isEmpty()
                ? DEFAULT_MOD_EXTENSIONS
                : modSyncExtensions.stream().map(e -> e.startsWith(".") ? e : "." + e)
                        .map(e -> e.toLowerCase(Locale.ROOT)).toList();
        modSyncExcludes = modSyncExcludes == null ? List.of() : List.copyOf(modSyncExcludes);
        echoServerOutput = echoServerOutput == null || echoServerOutput;
        clientSyncScope = blankToDefault(clientSyncScope, "MODS_ONLY").toUpperCase(Locale.ROOT);
        serverBindAll = serverBindAll != null && serverBindAll;
        serverJavaHome = serverJavaHome == null ? "" : serverJavaHome.trim();
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        activeWorldId = activeWorldId == null ? "" : activeWorldId.trim();
        serversRoot = serversRoot == null ? "" : serversRoot.trim();
        curseforgeApiKey = firstNonBlank(System.getenv("MCSYNC_CURSEFORGE_API_KEY"), curseforgeApiKey, "");
        cacheDirectory = cacheDirectory == null ? "" : cacheDirectory.trim();
        modSyncMode = modSyncMode.toUpperCase(Locale.ROOT);
    }

    /** v1.1 canonical constructor (kept for existing callers and tests). */
    public Config(String playerId, String localTailscaleIp, String storageBackend, String gitRemoteUrl,
                  String gitToken, String gitBranch, String s3Endpoint, String s3Region, String s3Bucket,
                  String s3AccessKey, String s3SecretKey, String serverDirectory, List<String> serverStartCommand,
                  String worldFolderName, List<String> clientLaunchCommand, String discordWebhookUrl,
                  Integer heartbeatIntervalSeconds, Integer lockTimeoutSeconds, Integer maxBackupsToKeep,
                  Integer serverPort, Boolean s3PathStyle, String s3KeyPrefix, List<String> whitelistPlayers,
                  Boolean acceptEula, Integer idleShutdownSeconds, Integer playerPollIntervalSeconds,
                  Integer initialJoinGraceSeconds, Integer serverStartTimeoutSeconds, Integer stopTimeoutSeconds,
                  Integer hostWaitTimeoutSeconds, String localClientAddress, String modSyncMode,
                  String clientInstanceDirectory, List<String> modSyncExtensions, List<String> modSyncExcludes,
                  Boolean echoServerOutput, String clientSyncScope, Boolean serverBindAll, String serverJavaHome) {
        this(playerId, localTailscaleIp, storageBackend, gitRemoteUrl, gitToken, gitBranch,
                s3Endpoint, s3Region, s3Bucket, s3AccessKey, s3SecretKey,
                serverDirectory, serverStartCommand, worldFolderName, clientLaunchCommand,
                discordWebhookUrl, heartbeatIntervalSeconds, lockTimeoutSeconds, maxBackupsToKeep,
                serverPort, s3PathStyle, s3KeyPrefix, whitelistPlayers, acceptEula,
                idleShutdownSeconds, playerPollIntervalSeconds, initialJoinGraceSeconds,
                serverStartTimeoutSeconds, stopTimeoutSeconds, hostWaitTimeoutSeconds,
                localClientAddress, modSyncMode, clientInstanceDirectory, modSyncExtensions,
                modSyncExcludes, echoServerOutput, clientSyncScope, serverBindAll, serverJavaHome,
                null, null, null, null, null);
    }

    /** Pre-1.1 canonical constructor (kept for existing callers and tests). */
    public Config(String playerId, String localTailscaleIp, String storageBackend, String gitRemoteUrl,
                  String gitToken, String gitBranch, String s3Endpoint, String s3Region, String s3Bucket,
                  String s3AccessKey, String s3SecretKey, String serverDirectory, List<String> serverStartCommand,
                  String worldFolderName, List<String> clientLaunchCommand, String discordWebhookUrl,
                  Integer heartbeatIntervalSeconds, Integer lockTimeoutSeconds, Integer maxBackupsToKeep,
                  Integer serverPort, Boolean s3PathStyle, String s3KeyPrefix, List<String> whitelistPlayers,
                  Boolean acceptEula, Integer idleShutdownSeconds, Integer playerPollIntervalSeconds,
                  Integer initialJoinGraceSeconds, Integer serverStartTimeoutSeconds, Integer stopTimeoutSeconds,
                  Integer hostWaitTimeoutSeconds, String localClientAddress, String modSyncMode,
                  String clientInstanceDirectory, List<String> modSyncExtensions, List<String> modSyncExcludes,
                  Boolean echoServerOutput) {
        this(playerId, localTailscaleIp, storageBackend, gitRemoteUrl, gitToken, gitBranch,
                s3Endpoint, s3Region, s3Bucket, s3AccessKey, s3SecretKey,
                serverDirectory, serverStartCommand, worldFolderName, clientLaunchCommand,
                discordWebhookUrl, heartbeatIntervalSeconds, lockTimeoutSeconds, maxBackupsToKeep,
                serverPort, s3PathStyle, s3KeyPrefix, whitelistPlayers, acceptEula,
                idleShutdownSeconds, playerPollIntervalSeconds, initialJoinGraceSeconds,
                serverStartTimeoutSeconds, stopTimeoutSeconds, hostWaitTimeoutSeconds,
                localClientAddress, modSyncMode, clientInstanceDirectory, modSyncExtensions,
                modSyncExcludes, echoServerOutput, null, null, null, null, null, null, null, null);
    }

    /** Backwards-compatible constructor for tests and existing callers. */
    public Config(
            String playerId,
            String localTailscaleIp,
            String s3Endpoint,
            String s3Region,
            String s3Bucket,
            String s3AccessKey,
            String s3SecretKey,
            String serverDirectory,
            List<String> serverStartCommand,
            String worldFolderName,
            List<String> clientLaunchCommand,
            String discordWebhookUrl,
            Integer heartbeatIntervalSeconds,
            Integer lockTimeoutSeconds,
            Integer maxBackupsToKeep,
            Integer serverPort,
            Boolean s3PathStyle,
            String s3KeyPrefix,
            List<String> whitelistPlayers,
            Boolean acceptEula,
            Integer idleShutdownSeconds,
            Integer playerPollIntervalSeconds,
            Integer initialJoinGraceSeconds,
            Integer serverStartTimeoutSeconds,
            Integer stopTimeoutSeconds,
            Integer hostWaitTimeoutSeconds,
            String localClientAddress,
            String modSyncMode,
            String clientInstanceDirectory,
            List<String> modSyncExtensions,
            List<String> modSyncExcludes,
            Boolean echoServerOutput
    ) {
        this(playerId, localTailscaleIp, "S3", "", "", "mcsync-state",
                s3Endpoint, s3Region, s3Bucket, s3AccessKey, s3SecretKey,
                serverDirectory, serverStartCommand, worldFolderName, clientLaunchCommand,
                discordWebhookUrl, heartbeatIntervalSeconds, lockTimeoutSeconds, maxBackupsToKeep,
                serverPort, s3PathStyle, s3KeyPrefix, whitelistPlayers, acceptEula,
                idleShutdownSeconds, playerPollIntervalSeconds, initialJoinGraceSeconds,
                serverStartTimeoutSeconds, stopTimeoutSeconds, hostWaitTimeoutSeconds,
                localClientAddress, modSyncMode, clientInstanceDirectory, modSyncExtensions,
                modSyncExcludes, echoServerOutput);
    }

    // ------------------------------------------------------------------------------------------
    // Loading & validation
    // ------------------------------------------------------------------------------------------

    public static Config load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Configuration file not found: " + file.toAbsolutePath()
                    + System.lineSeparator()
                    + "Copy config.example.json to config.json and fill in your values.");
        }
        ObjectMapper mapper = Json.mapper().copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Config cfg = mapper.readValue(file.toFile(), Config.class);
        cfg.validate();
        return cfg;
    }

    /** Throws {@link IllegalArgumentException} listing every invalid field. */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (isBlank(playerId)) errors.add("playerId is required");
        else if (!playerId.matches("[A-Za-z0-9_.-]{1,64}"))
            errors.add("playerId may only contain letters, digits, '_', '-', '.'");

        if (!List.of("GIT", "S3").contains(storageBackend)) {
            errors.add("storageBackend must be \"GIT\" or \"S3\"");
        } else if (isGitBackend()) {
            if (isBlank(gitRemoteUrl)) {
                errors.add("gitRemoteUrl is required when storageBackend is GIT (e.g. \"https://github.com/tuo-utente/tuo-repo-privato.git\")");
            }
        } else {
            if (isBlank(s3Endpoint)) errors.add("s3Endpoint is required when storageBackend is S3");
            if (isBlank(s3Bucket)) errors.add("s3Bucket is required when storageBackend is S3");
            if (isBlank(s3AccessKey)) errors.add("s3AccessKey is required (or env MCSYNC_S3_ACCESS_KEY)");
            if (isBlank(s3SecretKey)) errors.add("s3SecretKey is required (or env MCSYNC_S3_SECRET_KEY)");
        }

        if (isBlank(serverDirectory)) errors.add("serverDirectory is required");
        if (serverStartCommand.isEmpty()) errors.add("serverStartCommand must contain at least one element");
        if (worldFolderName.contains("/") || worldFolderName.contains("\\") || worldFolderName.contains(".."))
            errors.add("worldFolderName must be a plain folder name");
        if (!List.of("DIFFERENTIAL", "STRICT", "DISABLED", "LOCKED").contains(modSyncMode))
            errors.add("modSyncMode must be DIFFERENTIAL, STRICT, DISABLED or LOCKED");
        if (!List.of("MODS_ONLY", "ALL").contains(clientSyncScope))
            errors.add("clientSyncScope must be MODS_ONLY or ALL");
        if (heartbeatIntervalSeconds >= lockTimeoutSeconds)
            errors.add("heartbeatIntervalSeconds must be smaller than lockTimeoutSeconds");
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration:" + System.lineSeparator() + " - "
                    + String.join(System.lineSeparator() + " - ", errors));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Derived helpers
    // ------------------------------------------------------------------------------------------

    @JsonIgnore
    public boolean isGitBackend() {
        return "GIT".equalsIgnoreCase(storageBackend);
    }

    @JsonIgnore
    public Path serverDir() {
        return Path.of(expandHome(serverDirectory)).toAbsolutePath().normalize();
    }

    @JsonIgnore
    public Path worldDir() {
        return serverDir().resolve(worldFolderName);
    }

    @JsonIgnore
    public Path stateDir() {
        return serverDir().resolve(".mcsync");
    }

    /**
     * Shared local Git repository directory across all worlds belonging to the same remote repository,
     * preventing duplicate multi-clone checkouts inside {@code servers/<worldId>/.mcsync/git-store}.
     */
    @JsonIgnore
    public Path sharedGitRepoDir() {
        Path base;
        if (!isBlank(cacheDirectory)) {
            Path c = Path.of(expandHome(cacheDirectory)).toAbsolutePath().normalize();
            base = c.getFileName() != null && c.getFileName().toString().equalsIgnoreCase("cache") && c.getParent() != null
                    ? c.getParent()
                    : c.resolve(".mcsync");
        } else if (!isBlank(serversRoot)) {
            Path s = Path.of(expandHome(serversRoot)).toAbsolutePath().normalize();
            base = (s.getParent() != null ? s.getParent() : s).resolve(".mcsync");
        } else {
            Path srv = serverDir();
            if (srv.getParent() != null && srv.getParent().getFileName() != null
                    && srv.getParent().getFileName().toString().equalsIgnoreCase("servers")
                    && srv.getParent().getParent() != null) {
                base = srv.getParent().getParent().resolve(".mcsync");
            } else {
                base = srv.resolve(".mcsync");
            }
        }
        String urlKey = isBlank(gitRemoteUrl) ? "default"
                : Integer.toHexString(gitRemoteUrl.trim().toLowerCase(Locale.ROOT).hashCode());
        return base.resolve("git-store-" + urlKey);
    }

    /**
     * Client instance folder. Prism Launcher stores game files in {@code minecraft/} (no dot) while
     * vanilla/CurseForge use {@code .minecraft/}: if the configured folder does not exist, the
     * sibling variant is tried automatically.
     */
    @JsonIgnore
    public Path clientInstanceDir() {
        if (isBlank(clientInstanceDirectory)) return null;
        Path p = Path.of(expandHome(clientInstanceDirectory)).toAbsolutePath().normalize();
        if (Files.isDirectory(p) || p.getFileName() == null || p.getParent() == null) return p;
        String name = p.getFileName().toString();
        Path alt = null;
        if (name.equals(".minecraft")) alt = p.resolveSibling("minecraft");
        else if (name.equals("minecraft")) alt = p.resolveSibling(".minecraft");
        else if (Files.isDirectory(p.resolve("minecraft"))) alt = p.resolve("minecraft");
        else if (Files.isDirectory(p.resolve(".minecraft"))) alt = p.resolve(".minecraft");
        return alt != null && Files.isDirectory(alt) ? alt : p;
    }

    @JsonIgnore
    public boolean clientModsOnly() {
        return "MODS_ONLY".equals(clientSyncScope);
    }

    /**
     * Address the local Minecraft client of the host must connect to.
     *
     * <p>When the server binds only to the Tailscale interface ({@code server-ip=<tailscaleIp>}),
     * {@code 127.0.0.1} is NOT listening and the client fails with
     * {@code Connection timed out: getsockopt}. Therefore {@code "auto"}, blank, {@code 127.0.0.1}
     * and {@code localhost} resolve to the Tailscale IP unless {@code serverBindAll} is true.</p>
     */
    @JsonIgnore
    public String effectiveClientAddress() {
        String a = localClientAddress == null ? "" : localClientAddress.trim();
        boolean loopbackish = a.isEmpty() || a.equalsIgnoreCase("auto") || a.equals("127.0.0.1")
                || a.equalsIgnoreCase("localhost");
        if (!loopbackish) return a;
        if (serverBindAll) return a.isEmpty() || a.equalsIgnoreCase("auto") ? "127.0.0.1" : a;
        return localTailscaleIp;
    }

    /** Value written to {@code server-ip} in server.properties ("" = all interfaces). */
    @JsonIgnore
    public String serverBindAddress() {
        return serverBindAll ? "" : localTailscaleIp;
    }

    /** Address used for local liveness probes of our own server. */
    @JsonIgnore
    public String localProbeAddress() {
        return serverBindAll ? "127.0.0.1" : localTailscaleIp;
    }

    @JsonIgnore
    public boolean discordEnabled() {
        return !isBlank(discordWebhookUrl);
    }

    // ------------------------------------------------------------------------------------------
    // Multi-world helpers
    // ------------------------------------------------------------------------------------------

    /** Prefix of every key of a world inside the shared repo/bucket: {@code <base>worlds/<id>/}. */
    public String worldKeyPrefix(String worldId) {
        return s3KeyPrefix + "worlds/" + worldId + "/";
    }

    public java.util.Optional<WorldProfile> findWorld(String id) {
        return worlds.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    /** The world selected in the GUI / CLI default, if any. */
    @JsonIgnore
    public java.util.Optional<WorldProfile> activeWorld() {
        if (!activeWorldId.isEmpty()) return findWorld(activeWorldId);
        return worlds.size() == 1 ? java.util.Optional.of(worlds.get(0)) : java.util.Optional.empty();
    }

    /** Returns a copy with {@code world} added or replaced by id. */
    public Config withWorld(WorldProfile world) {
        List<WorldProfile> list = new ArrayList<>(worlds);
        list.removeIf(w -> w.id().equals(world.id()));
        list.add(world);
        return with(Map.of("worlds", list));
    }

    public Config withoutWorld(String id) {
        List<WorldProfile> list = new ArrayList<>(worlds);
        list.removeIf(w -> w.id().equals(id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("worlds", list);
        if (activeWorldId.equals(id)) m.put("activeWorldId", "");
        return with(m);
    }

    public Config withActiveWorld(String id) {
        return with(Map.of("activeWorldId", id == null ? "" : id));
    }

    /**
     * Derives the configuration used to operate on a single world: every storage key lives under
     * {@code worlds/<id>/} (the "prefixed storage"), and the server / Prism folders are the world's.
     * Everything else (lock, world sync, backups) keeps working with relative keys, unchanged.
     */
    public Config forWorld(WorldProfile w, Map<String, ?> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        String repoPrefix = s3KeyPrefix;
        Invite.Storage st = w.storage();
        if (st != null) {
            // this world lives in another repo/bucket than the global one
            m.put("storageBackend", st.backend());
            m.put("gitRemoteUrl", st.remoteUrl());
            m.put("gitBranch", st.branch());
            m.put("gitToken", st.token());
            if (st.s3Endpoint() != null) m.put("s3Endpoint", st.s3Endpoint());
            if (st.s3Region() != null) m.put("s3Region", st.s3Region());
            if (st.s3Bucket() != null) m.put("s3Bucket", st.s3Bucket());
            if (st.s3AccessKey() != null) m.put("s3AccessKey", st.s3AccessKey());
            if (st.s3SecretKey() != null) m.put("s3SecretKey", st.s3SecretKey());
            if (st.s3PathStyle() != null) m.put("s3PathStyle", st.s3PathStyle());
            repoPrefix = normalisePrefix(st.prefix());
        }
        m.put("s3KeyPrefix", repoPrefix + "worlds/" + w.id() + "/");
        m.put("serverDirectory", w.serverDirectory());
        m.put("clientInstanceDirectory", w.clientInstanceDirectory());
        m.put("activeWorldId", w.id());
        if (extra != null) m.putAll(extra);
        return with(m);
    }

    /** Copy of this config pointing at the repo root (registry and shared files, no world prefix). */
    @JsonIgnore
    public Config atRepoRoot() {
        String suffix = "worlds/" + activeWorldId + "/";
        if (!activeWorldId.isEmpty() && s3KeyPrefix.endsWith(suffix)) {
            return with(Map.of("s3KeyPrefix", s3KeyPrefix.substring(0, s3KeyPrefix.length() - suffix.length())));
        }
        return this;
    }

    /** Returns a copy with the resolved Tailscale IP (used after auto-detection). */
    public Config withLocalTailscaleIp(String ip) {
        return with(Map.of("localTailscaleIp", ip));
    }

    public Config withServerStartCommand(List<String> cmd) {
        return with(Map.of("serverStartCommand", cmd));
    }

    public Config withClientLaunchCommand(List<String> cmd) {
        return with(Map.of("clientLaunchCommand", cmd));
    }

    /** Returns a copy where the given JSON properties are replaced. */
    public Config with(Map<String, ?> overrides) {
        ObjectMapper mapper = Json.mapper().copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.convertValue(this, LinkedHashMap.class);
        m.putAll(overrides);
        return mapper.convertValue(m, Config.class);
    }

    @Override
    public String toString() {
        return "Config[playerId=" + playerId + ", tailscaleIp=" + localTailscaleIp
                + ", backend=" + storageBackend
                + (isGitBackend() ? ", gitRemote=" + gitRemoteUrl + ", branch=" + gitBranch
                : ", endpoint=" + s3Endpoint + ", bucket=" + s3Bucket)
                + ", serverDirectory=" + serverDirectory + ", world=" + worldFolderName + "]";
    }

    // ------------------------------------------------------------------------------------------

    public static String expandHome(String p) {
        String s = p.trim();
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([A-Za-z0-9_]+)%").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = System.getenv(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v != null ? v : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalisePrefix(String prefix) {
        if (isBlank(prefix)) return "";
        String p = prefix.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p.endsWith("/") ? p : p + "/";
    }

    private static Integer positiveOrDefault(Integer v, int def) {
        return v == null || v <= 0 ? def : v;
    }

    private static String blankToDefault(String v, String def) {
        return isBlank(v) ? def : v.trim();
    }

    private static String firstNonBlank(String a, String b, String def) {
        if (!isBlank(a)) return a.trim();
        if (!isBlank(b)) return b.trim();
        return def;
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
