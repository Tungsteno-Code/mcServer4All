package com.mcsync.world;

import com.mcsync.model.ModsLock;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Glob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Turns an unpacked modpack (+ optional server pack) into a {@link ModsLock} and the list of files
 * that must be stored as blobs in the repo (jars without a public source, pinned configs, server files).
 */
public final class LockBuilder {

    private static final Logger log = LoggerFactory.getLogger(LockBuilder.class);

    /** Configs that clients must have identical but that the loaders do not send at login. */
    public static final List<String> DEFAULT_PINNED_CONFIGS = List.of(
            "config/**/*-common.toml", "kubejs/**", "config/paxi/**", "config/openloader/**");

    /** Top-level folders never shipped as server files. */
    static final Set<String> SERVER_FILES_DENY = Set.of("mods", "libraries", "world", "logs", "crash-reports",
            ".mcsync", "cache", "versions", "backups", ".fabric", ".git", "__macosx", "resourcepacks", "shaderpacks",
            "saves", "screenshots", "journeymap", "xaeroworldmap", "xaerowaypoints", "downloads", "local", "debug");

    static final long MAX_SERVER_FILE_BYTES = 64L * 1024 * 1024;

    /** @param blobs sha256 -&gt; file to upload under {@code modfiles/<sha256>} */
    public record Built(ModsLock lock, Map<String, Path> blobs, Map<String, Path> allFiles, List<String> notes) {
    }

    private final SourceResolver resolver;
    private final ModSideDetector sides;
    private final Glob pinned;
    private final Glob clientOnlyConfigs;

    public LockBuilder(SourceResolver resolver, List<String> extraClientOnly, List<String> pinnedGlobs,
                       List<String> clientOnlyConfigGlobs) {
        this.resolver = resolver == null ? SourceResolver.NONE : resolver;
        this.sides = new ModSideDetector(extraClientOnly);
        List<String> p = new ArrayList<>(DEFAULT_PINNED_CONFIGS);
        if (pinnedGlobs != null) p.addAll(pinnedGlobs);
        this.pinned = new Glob(p);
        this.clientOnlyConfigs = new Glob(clientOnlyConfigGlobs == null ? List.of() : clientOnlyConfigGlobs);
    }

    public Built build(PackStage client, Path serverPackRoot, long version, String by) throws IOException {
        List<String> notes = new ArrayList<>();
        Map<String, ModsLock.Entry> entries = new TreeMap<>();
        Map<String, Path> blobs = new LinkedHashMap<>();
        Map<String, Path> all = new LinkedHashMap<>();

        // ---- server pack jars (sha -> relative name) ------------------------------------------
        Map<String, Path> serverJars = new TreeMap<>();
        Map<String, String> serverJarShas = new HashMap<>();
        if (serverPackRoot != null) {
            for (Map.Entry<String, Path> e : listJars(serverPackRoot).entrySet()) {
                serverJars.put(e.getKey(), e.getValue());
                serverJarShas.put(FileUtils.sha256(e.getValue()), e.getKey());
            }
        }

        // ---- client jars (with batch CurseForge Murmur2 lookup first) ------------------------
        Map<String, Path> clientJarList = listJars(client.root());
        Map<String, Path> unknownSourceJars = new LinkedHashMap<>();
        clientJarList.forEach((rel, jar) -> {
            if (!client.knownSources().containsKey(rel)) {
                unknownSourceJars.put(rel, jar);
            }
        });
        Map<String, ModsLock.Source> batchResolved = resolver.resolveBatch(unknownSourceJars);

        Set<String> serverShasSeen = new HashSet<>();
        for (Map.Entry<String, Path> e : clientJarList.entrySet()) {
            String rel = e.getKey();
            Path jar = e.getValue();
            String sha = FileUtils.sha256(jar);
            String side;
            if (client.knownSides().containsKey(rel)) {
                side = client.knownSides().get(rel);
            } else if (serverPackRoot != null) {
                side = serverJarShas.containsKey(sha) ? ModsLock.SIDE_BOTH : ModsLock.SIDE_CLIENT;
                if (ModsLock.SIDE_BOTH.equals(side) && ModsLock.SIDE_CLIENT.equals(sides.detect(rel, jar))) {
                    side = ModsLock.SIDE_BOTH; // the server pack ships it: keep it
                }
            } else {
                side = sides.detect(rel, jar);
            }
            serverShasSeen.add(sha);
            all.putIfAbsent(sha, jar);
            ModsLock.Source known = client.knownSources().getOrDefault(rel, batchResolved.get(rel));
            entries.put(rel, entry(rel, jar, sha, side, known, blobs));
        }
        // ---- jars only present in the server pack -> server side -----------------------------
        for (Map.Entry<String, Path> e : serverJars.entrySet()) {
            String sha = FileUtils.sha256(e.getValue());
            if (serverShasSeen.contains(sha)) continue;
            String rel = e.getKey();
            if (entries.containsKey(rel)) rel = "mods/server-" + rel.substring(rel.lastIndexOf('/') + 1);
            all.putIfAbsent(sha, e.getValue());
            entries.put(rel, entry(rel, e.getValue(), sha, ModsLock.SIDE_SERVER, null, blobs));
        }
        long clientOnly = entries.values().stream().filter(x -> ModsLock.SIDE_CLIENT.equals(x.side())).count();
        long serverOnly = entries.values().stream().filter(x -> ModsLock.SIDE_SERVER.equals(x.side())).count();
        notes.add(entries.size() + " mod (" + clientOnly + " solo client, " + serverOnly + " solo server)");

        // ---- overrides bundle (.tar.zst parts < 75 MB) instead of thousands of loose files ---
        Map<String, Path> overridesSource = new TreeMap<>(collect(client.root(), SERVER_FILES_DENY));
        if (serverPackRoot != null) {
            overridesSource.putAll(collect(serverPackRoot, SERVER_FILES_DENY));
        }
        if (client.serverOverrides() != null) {
            overridesSource.putAll(collect(client.serverOverrides(), Set.of()));
        }

        Map<String, Path> filteredOverrides = new TreeMap<>();
        for (Map.Entry<String, Path> e : overridesSource.entrySet()) {
            String rel = e.getKey();
            // Keep options.txt if shipped by the modpack, skip other top-level generated files like instance.cfg / mmc-pack.json
            if (rel.indexOf('/') < 0 && !rel.equalsIgnoreCase("options.txt") && !rel.equalsIgnoreCase("optionsof.txt")) {
                continue;
            }
            if (Files.size(e.getValue()) > MAX_SERVER_FILE_BYTES) {
                notes.add("file override troppo grande, ignorato: " + rel);
                continue;
            }
            filteredOverrides.put(rel, e.getValue());
        }

        Map<String, String> overridesBundles = new TreeMap<>();
        if (!filteredOverrides.isEmpty()) {
            Path bundleDir = Files.createTempDirectory("mcsync-overrides-bundle-");
            OverridesBundler.BundleResult bundled = OverridesBundler.pack(filteredOverrides, bundleDir);
            overridesBundles.putAll(bundled.bundleShas());
            bundled.blobsBySha().forEach((sha, archivePath) -> {
                blobs.putIfAbsent(sha, archivePath);
                all.putIfAbsent(sha, archivePath);
            });
            notes.add(bundled.fileCount() + " file override compressi in " + overridesBundles.size()
                    + " bundle Zstd (" + FileUtils.humanSize(bundled.totalBytes()) + " non compressi)");
        }

        ModsLock lock = new ModsLock(ModsLock.SCHEMA_VERSION, version, client.minecraft(), client.loader(),
                entries, Map.of(), Map.of(), overridesBundles, by, Instant.now().getEpochSecond());
        long blobBytes = 0;
        for (Path p : blobs.values()) blobBytes += Files.size(p);
        notes.add(blobs.size() + " file nello store del repo (" + FileUtils.humanSize(blobBytes) + ")");
        return new Built(lock, blobs, all, notes);
    }

    private ModsLock.Entry entry(String rel, Path jar, String sha, String side, ModsLock.Source known,
                                 Map<String, Path> blobs) throws IOException {
        long size = Files.size(jar);
        ModsLock.Source src = known;
        if (src == null) {
            String sha1 = null;
            try {
                sha1 = PackReader.hex("SHA-1", jar);
            } catch (java.security.NoSuchAlgorithmException ignored) {
                // never happens
            }
            src = sha1 == null ? null : resolver.resolve(jar, sha1, sha).orElse(null);
        }
        if (src == null || ModsLock.SRC_BLOB.equals(src.type())) {
            blobs.putIfAbsent(sha, jar);
            src = ModsLock.Source.blob();
        }
        return new ModsLock.Entry(sha, size, side, src);
    }

    static Map<String, Path> listJars(Path root) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods)) return out;
        try (Stream<Path> s = Files.walk(mods)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    out.put(FileUtils.toPortable(root.relativize(p)), p);
                }
            }
        }
        return out;
    }

    /** Regular files under {@code root}, skipping top-level folders in {@code denyTop} (lowercase). */
    static Map<String, Path> collect(Path root, Set<String> denyTop) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = FileUtils.toPortable(root.relativize(p));
                int slash = rel.indexOf('/');
                String top = (slash < 0 ? rel : rel.substring(0, slash)).toLowerCase(Locale.ROOT);
                if (denyTop.contains(top)) continue;
                out.put(rel, p);
            }
        }
        return out;
    }
}
