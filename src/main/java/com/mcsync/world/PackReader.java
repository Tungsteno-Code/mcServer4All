package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.model.ModsLock;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Reads the four supported modpack formats into a {@link PackStage} (Minecraft + loader + files). */
public final class PackReader {

    private static final Logger log = LoggerFactory.getLogger(PackReader.class);

    private final String curseforgeApiKey;
    private final String curseforgeApiBase;

    public PackReader(String curseforgeApiKey) {
        this(curseforgeApiKey, "https://api.curseforge.com/v1");
    }

    public PackReader(String curseforgeApiKey, String curseforgeApiBase) {
        this.curseforgeApiKey = curseforgeApiKey == null ? "" : curseforgeApiKey;
        this.curseforgeApiBase = curseforgeApiBase;
    }

    public PackStage read(ModpackSource source, Path workDir) throws IOException {
        if (source == null) throw new IOException("Modpack mancante");
        Path p = source.path();
        if (p == null || !Files.exists(p)) throw new IOException("Modpack non trovato: " + p);
        return switch (source) {
            case ModpackSource.CurseForgeZip z -> readCurseForge(z.zip(), workDir);
            case ModpackSource.ModrinthMrpack m -> readMrpack(m.mrpack(), workDir);
            case ModpackSource.PrismInstance i -> readPrism(i.instanceDir());
            case ModpackSource.Folder f -> readFolder(f.dir());
        };
    }

    // ------------------------------------------------------------------------------------------
    // Folder & Prism
    // ------------------------------------------------------------------------------------------

    private PackStage readFolder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Non è una cartella: " + dir);
        Path root = Files.isDirectory(dir.resolve("mods")) ? dir : gameDirOf(dir);
        if (!Files.isDirectory(root.resolve("mods"))) {
            throw new IOException("La cartella non contiene mods/: " + dir);
        }
        // A folder has no metadata: infer loader from the instance files if possible.
        ModsLock.Loader loader = null;
        String mc = null;
        Path mmc = dir.resolve("mmc-pack.json");
        if (Files.isRegularFile(mmc)) {
            PrismMeta meta = prismMeta(mmc);
            mc = meta.minecraft();
            loader = meta.loader();
        }
        if (mc == null || loader == null) {
            throw new IOException("Impossibile dedurre versione di Minecraft e loader da una cartella semplice: "
                    + "usa un'istanza Prism, uno zip CurseForge o un .mrpack, oppure aggiungi mmc-pack.json");
        }
        return new PackStage(mc, loader, root, Map.of(), Map.of(), null, false);
    }

    private static Path gameDirOf(Path instance) {
        if (Files.isDirectory(instance.resolve("minecraft"))) return instance.resolve("minecraft");
        if (Files.isDirectory(instance.resolve(".minecraft"))) return instance.resolve(".minecraft");
        return instance;
    }

    private PackStage readPrism(Path instanceDir) throws IOException {
        Path mmc = instanceDir.resolve("mmc-pack.json");
        if (!Files.isRegularFile(mmc)) throw new IOException("mmc-pack.json non trovato in " + instanceDir);
        PrismMeta meta = prismMeta(mmc);
        if (meta.minecraft() == null) throw new IOException("Versione di Minecraft assente in " + mmc);
        if (meta.loader() == null) throw new IOException("Nessun mod loader (Forge/NeoForge/Fabric/Quilt) in " + mmc);
        Path game = gameDirOf(instanceDir);
        if (!Files.isDirectory(game.resolve("mods"))) throw new IOException("L'istanza non ha una cartella mods/: " + game);
        return new PackStage(meta.minecraft(), meta.loader(), game, Map.of(), Map.of(), null, false);
    }

    record PrismMeta(String minecraft, ModsLock.Loader loader) {
    }

    static PrismMeta prismMeta(Path mmcPack) throws IOException {
        return prismMeta(Json.mapper().readTree(mmcPack.toFile()));
    }

    static PrismMeta prismMetaFromBytes(byte[] json) throws IOException {
        return prismMeta(Json.mapper().readTree(json));
    }

    private static PrismMeta prismMeta(JsonNode root) {
        String mc = null;
        ModsLock.Loader loader = null;
        for (JsonNode c : root.path("components")) {
            String uid = c.path("uid").asText("");
            String ver = c.path("version").asText(c.path("cachedVersion").asText(""));
            if (ver.isEmpty()) continue;
            switch (uid) {
                case "net.minecraft" -> mc = ver;
                case "net.minecraftforge" -> loader = new ModsLock.Loader("forge", ver);
                case "net.neoforged" -> loader = new ModsLock.Loader("neoforge", ver);
                case "net.fabricmc.fabric-loader" -> loader = new ModsLock.Loader("fabric", ver);
                case "org.quiltmc.quilt-loader" -> loader = new ModsLock.Loader("quilt", ver);
                default -> {
                }
            }
        }
        return new PrismMeta(mc, loader);
    }

    // ------------------------------------------------------------------------------------------
    // CurseForge .zip (manifest.json + overrides/)
    // ------------------------------------------------------------------------------------------

    private PackStage readCurseForge(Path zip, Path workDir) throws IOException {
        byte[] mf = Zips.read(zip, "manifest.json");
        if (mf == null) throw new IOException("manifest.json non trovato in " + zip.getFileName() + " (non è un modpack CurseForge)");
        JsonNode m = Json.mapper().readTree(mf);
        String mc = m.path("minecraft").path("version").asText(null);
        String loaderId = null;
        for (JsonNode l : m.path("minecraft").path("modLoaders")) {
            if (loaderId == null || l.path("primary").asBoolean(false)) loaderId = l.path("id").asText(null);
        }
        ModsLock.Loader loader = parseCurseForgeLoader(loaderId);
        if (mc == null || loader == null) throw new IOException("manifest.json senza versione Minecraft o mod loader valido");

        Path root = Files.createTempDirectory(workDir(workDir), "cfpack-");
        String overrides = m.path("overrides").asText("overrides");
        Zips.extract(zip, overrides.endsWith("/") ? overrides : overrides + "/", root);
        Files.createDirectories(root.resolve("mods"));

        Map<String, ModsLock.Source> sources = new java.util.concurrent.ConcurrentHashMap<>();
        JsonNode files = m.path("files");
        if (files.size() > 0 && curseforgeApiKey.isBlank()) {
            throw new IOException("Il modpack CurseForge elenca " + files.size() + " mod da scaricare: serve una API key "
                    + "CurseForge (config.json \"curseforgeApiKey\" o variabile MCSYNC_CURSEFORGE_API_KEY). "
                    + "In alternativa importa il pack in Prism e usa la sorgente \"istanza Prism\".");
        }
        if (files.size() > 0) {
            Map<String, String> headers = Map.of("x-api-key", curseforgeApiKey, "Accept", "application/json");
            Map<Long, Long> fileToProject = new HashMap<>();
            var reqRoot = Json.mapper().createObjectNode();
            var fileIdsArr = reqRoot.putArray("fileIds");
            for (JsonNode f : files) {
                long pid = f.path("projectID").asLong();
                long fid = f.path("fileID").asLong();
                fileToProject.put(fid, pid);
                fileIdsArr.add(fid);
            }

            Map<Long, JsonNode> resolvedMeta = new HashMap<>();
            try {
                String batchBody = Http.postJson(curseforgeApiBase + "/mods/files", headers,
                        Json.mapper().writeValueAsString(reqRoot));
                if (batchBody != null) {
                    for (JsonNode d : Json.mapper().readTree(batchBody).path("data")) {
                        resolvedMeta.put(d.path("id").asLong(), d);
                    }
                }
            } catch (IOException batchEx) {
                log.debug("Batch POST /mods/files fallback to single GET: {}", batchEx.getMessage());
            }

            // Fallback to individual GET only for files not returned by batch (e.g. mock test server)
            for (Map.Entry<Long, Long> e : fileToProject.entrySet()) {
                long fid = e.getKey();
                long pid = e.getValue();
                if (!resolvedMeta.containsKey(fid)) {
                    String body = Http.getString(curseforgeApiBase + "/mods/" + pid + "/files/" + fid, headers);
                    if (body == null) throw new IOException("File CurseForge non trovato: " + pid + ":" + fid);
                    resolvedMeta.put(fid, Json.mapper().readTree(body).path("data"));
                }
            }

            int totalMods = resolvedMeta.size();
            log.info("Scaricamento parallelo (8 thread) di {} mod CurseForge...", totalMods);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(8, Math.max(1, totalMods)), r -> {
                        Thread t = new Thread(r, "cf-pack-dl");
                        t.setDaemon(true);
                        return t;
                    });
            java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            java.util.List<String> blocked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

            for (Map.Entry<Long, JsonNode> entry : resolvedMeta.entrySet()) {
                long fid = entry.getKey();
                JsonNode d = entry.getValue();
                long pid = d.path("modId").asLong(fileToProject.getOrDefault(fid, 0L));
                futures.add(pool.submit(() -> {
                    String name = d.path("fileName").asText(pid + "-" + fid + ".jar");
                    String safeName = Path.of(name).getFileName().toString();
                    String url = d.path("downloadUrl").asText("");
                    if (url.isBlank()) {
                        url = SourceResolver.CurseForgeFingerprint.edgeCdnFallbackUrl(fid, safeName);
                    }
                    if (url == null || url.isBlank()) {
                        // Try Modrinth by SHA-1 if available in CurseForge hashes array (algo=1 is SHA-1)
                        String sha1 = extractCfSha1(d);
                        if (sha1 != null) {
                            var mr = new SourceResolver.Modrinth().resolve(Path.of(safeName), sha1, "");
                            if (mr.isPresent() && mr.get().url() != null) {
                                url = mr.get().url();
                            }
                        }
                    }
                    if (url == null || url.isBlank()) {
                        blocked.add(safeName + " (" + pid + ":" + fid + ")");
                        return null;
                    }
                    Path target = root.resolve("mods").resolve(safeName);
                    try {
                        Http.download(url, Map.of(), target);
                        int cur = doneCount.incrementAndGet();
                        log.info("Download mod [{}/{}] ({}%) - {}", cur, totalMods, (cur * 100) / totalMods, safeName);
                        String rel = "mods/" + safeName;
                        sources.put(rel, new ModsLock.Source(ModsLock.SRC_CURSEFORGE, pid + ":" + fid, url));
                        return null;
                    } catch (IOException dlEx) {
                        // Try Modrinth fallback if edge CDN failed
                        String sha1 = extractCfSha1(d);
                        if (sha1 != null) {
                            var mr = new SourceResolver.Modrinth().resolve(Path.of(safeName), sha1, "");
                            if (mr.isPresent() && mr.get().url() != null) {
                                try {
                                    Http.download(mr.get().url(), Map.of(), target);
                                    int cur = doneCount.incrementAndGet();
                                    log.info("Download mod [{}/{}] ({}%) [Modrinth fallback] - {}", cur, totalMods, (cur * 100) / totalMods, safeName);
                                    sources.put("mods/" + safeName, mr.get());
                                    return null;
                                } catch (IOException ignored) {
                                }
                            }
                        }
                        blocked.add(safeName + " (" + pid + ":" + fid + ")");
                        return null;
                    }
                }));
            }
            pool.shutdown();
            for (var fut : futures) {
                try {
                    fut.get();
                } catch (Exception e) {
                    throw new IOException("Errore durante il download parallelo del modpack: " + e.getMessage(), e);
                }
            }
            if (!blocked.isEmpty()) {
                throw new IOException("Queste mod vietano il download da applicazioni di terze parti, scaricale a mano "
                        + "e usa la sorgente \"istanza Prism\"/\"cartella\": " + String.join(", ", blocked));
            }
        }
        return new PackStage(mc, loader, root, sources, Map.of(), null, true);
    }

    private static String extractCfSha1(JsonNode fileData) {
        for (JsonNode h : fileData.path("hashes")) {
            if (h.path("algo").asInt() == 1) {
                String v = h.path("value").asText("");
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    static ModsLock.Loader parseCurseForgeLoader(String id) {
        if (id == null) return null;
        int dash = id.indexOf('-');
        if (dash <= 0) return null;
        String type = id.substring(0, dash).toLowerCase(Locale.ROOT);
        String ver = id.substring(dash + 1);
        return switch (type) {
            case "forge", "neoforge", "fabric", "quilt" -> new ModsLock.Loader(type, ver);
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Modrinth .mrpack
    // ------------------------------------------------------------------------------------------

    private PackStage readMrpack(Path mrpack, Path workDir) throws IOException {
        byte[] idx = Zips.read(mrpack, "modrinth.index.json");
        if (idx == null) throw new IOException("modrinth.index.json non trovato in " + mrpack.getFileName());
        JsonNode m = Json.mapper().readTree(idx);
        JsonNode deps = m.path("dependencies");
        String mc = deps.path("minecraft").asText(null);
        ModsLock.Loader loader = null;
        for (var e : java.util.List.of(Map.entry("forge", "forge"), Map.entry("neoforge", "neoforge"),
                Map.entry("fabric-loader", "fabric"), Map.entry("quilt-loader", "quilt"))) {
            if (deps.has(e.getKey())) loader = new ModsLock.Loader(e.getValue(), deps.path(e.getKey()).asText());
        }
        if (mc == null || loader == null) throw new IOException("modrinth.index.json senza minecraft/loader");

        Path root = Files.createTempDirectory(workDir(workDir), "mrpack-");
        Zips.extract(mrpack, "overrides/", root);
        Path serverOver = null;
        if (Zips.hasEntryWithPrefix(mrpack, "server-overrides/")) {
            serverOver = Files.createTempDirectory(workDir(workDir), "mrpack-srv-");
            Zips.extract(mrpack, "server-overrides/", serverOver);
        }
        if (Zips.hasEntryWithPrefix(mrpack, "client-overrides/")) {
            // client-only extras (shaders, resourcepacks, client mods): jars become client-side mods
            Path tmp = Files.createTempDirectory(workDir(workDir), "mrpack-cli-");
            Zips.extract(mrpack, "client-overrides/", tmp);
            try (Stream<Path> s = Files.walk(tmp.resolve("mods"), 1)) {
                for (Path jar : s.filter(x -> x.toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                    Files.createDirectories(root.resolve("mods"));
                    Files.copy(jar, root.resolve("mods").resolve(jar.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.nio.file.NoSuchFileException ignored) {
                // no client mods
            }
            FileUtils.deleteRecursively(tmp);
        }

        Map<String, ModsLock.Source> sources = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, String> sides = new java.util.concurrent.ConcurrentHashMap<>();
        JsonNode files = m.path("files");
        int totalFiles = files.size();
        if (totalFiles > 0) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(8, totalFiles), r -> {
                        Thread t = new Thread(r, "mrpack-dl");
                        t.setDaemon(true);
                        return t;
                    });
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<>();
            for (JsonNode f : files) {
                futures.add(pool.submit(() -> {
                    String rel = f.path("path").asText("");
                    Path target = root.resolve(rel).normalize();
                    if (rel.isBlank() || !target.startsWith(root)) {
                        throw new IOException("Percorso non sicuro nel pack: " + rel);
                    }
                    String url = null;
                    for (JsonNode u : f.path("downloads")) {
                        if (u.asText("").startsWith("https://")) {
                            url = u.asText();
                            break;
                        }
                    }
                    if (url == null) throw new IOException("Nessun URL https per " + rel);
                    Http.download(url, Map.of(), target);
                    verifyHash(target, f.path("hashes"), rel);
                    int cur = done.incrementAndGet();
                    log.info("Download mod [{}/{}] ({}%) - {}", cur, totalFiles, (cur * 100) / totalFiles, rel);
                    String client = f.path("env").path("client").asText("required");
                    String server = f.path("env").path("server").asText("required");
                    String side = "unsupported".equals(server) ? ModsLock.SIDE_CLIENT
                            : "unsupported".equals(client) ? ModsLock.SIDE_SERVER : ModsLock.SIDE_BOTH;
                    if (rel.startsWith("mods/")) {
                        sides.put(rel, side);
                        sources.put(rel, new ModsLock.Source(ModsLock.SRC_MODRINTH, null, url));
                    }
                    return null;
                }));
            }
            pool.shutdown();
            for (var fut : futures) {
                try {
                    fut.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof IOException ioe) throw ioe;
                    throw new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrotto", e);
                }
            }
        }
        return new PackStage(mc, loader, root, sources, sides, serverOver, true);
    }

    private static void verifyHash(Path file, JsonNode hashes, String rel) throws IOException {
        String sha512 = hashes.path("sha512").asText("");
        String sha1 = hashes.path("sha1").asText("");
        try {
            if (!sha512.isEmpty()) {
                if (!hex("SHA-512", file).equalsIgnoreCase(sha512)) throw new IOException("Hash sha512 errato per " + rel);
            } else if (!sha1.isEmpty()) {
                if (!hex("SHA-1", file).equalsIgnoreCase(sha1)) throw new IOException("Hash sha1 errato per " + rel);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String hex(String algo, Path file) throws IOException, java.security.NoSuchAlgorithmException {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance(algo);
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    private static Path workDir(Path workDir) throws IOException {
        Files.createDirectories(workDir);
        return workDir;
    }
}
