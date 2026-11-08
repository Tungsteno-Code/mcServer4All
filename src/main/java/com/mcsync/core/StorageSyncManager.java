package com.mcsync.core;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mcsync.config.Config;
import com.mcsync.model.WorldManifest;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.FileUtils;
import com.mcsync.util.Json;
import com.mcsync.util.LocalStateStore;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * World synchronisation &amp; archive manager.
 *
 * <h3>Option 1: Per-File/Region Deduplicated Sync (Zero History Bloat on Git)</h3>
 * <ul>
 *     <li>Every file in the world folder ({@code region/r.X.Z.mca}, {@code level.dat},
 *     {@code playerdata/<uuid>.dat}, ...) is indexed by its SHA-256 hash inside
 *     {@code manifest.json -> worldFiles} and stored compressed at {@code world_blobs/<sha256>.zst}.</li>
 *     <li><b>On session end ({@link #pushWorld})</b>:
 *     we scan the local world, compare each file's SHA-256 against the remote {@code manifest.json},
 *     compress and upload <b>ONLY the files created or modified during the last session</b>,
 *     and delete superseded blobs so the repository never accumulates old versions.</li>
 *     <li><b>On session start ({@link #pullWorld})</b>:
 *     we compare {@code manifest.worldFiles()} against the local world folder on disk, back up
 *     any overwritten/deleted files to {@code .local_backup/world_pre_sync.tar.zst}, and download +
 *     decompress <b>ONLY the files whose SHA-256 differs from the local copy</b>!</li>
 * </ul>
 *
 * <p>Also retains full streaming {@code .tar.zst} archive/extract support for S3 snapshots and
 * local safety backups.</p>
 */
public final class StorageSyncManager {

    private static final Logger log = LoggerFactory.getLogger(StorageSyncManager.class);

    public static final String MANIFEST_KEY = "manifest.json";
    public static final String LATEST_KEY = "world_latest.tar.zst";
    public static final String WORLD_BLOBS_PREFIX = "world_blobs/";
    public static final String BACKUP_PREFIX = "backups/";
    public static final String CONFLICT_PREFIX = "conflicts/";
    private static final String ARCHIVE_TYPE = "application/zstd";
    private static final Pattern BACKUP_NAME = Pattern.compile("backups/world_v(\\d+)_(\\d+)\\.tar\\.zst");
    /** Files never synced: lock files held by a running server. */
    private static final Set<String> EXCLUDED_FILES = Set.of("session.lock");

    private final Config cfg;
    private final S3Storage storage;
    private final LocalStateStore state;

    public record ArchiveResult(Path file, String sha256, long sizeBytes) {
    }

    public record PushResult(WorldManifest manifest, long archiveBytes) {
    }

    public StorageSyncManager(Config cfg, S3Storage storage, LocalStateStore state) {
        this.cfg = cfg;
        this.storage = storage;
        this.state = state;
    }

    // ==========================================================================================
    // Per-file Zstd compression & world scanning (Option 1)
    // ==========================================================================================

    /** Scans the world directory (after stripping Data.Player from level.dat) and returns a sorted map {@code relativePath -> sha256}. */
    public static Map<String, String> scanWorldFiles(Path worldDir) throws IOException {
        Map<String, String> map = new TreeMap<>();
        if (!Files.isDirectory(worldDir)) return map;
        LevelDatCleaner.cleanWorldDir(worldDir);
        try (Stream<Path> walk = Files.walk(worldDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (EXCLUDED_FILES.contains(p.getFileName().toString())) continue;
                String rel = FileUtils.toPortable(worldDir.relativize(p));
                map.put(rel, FileUtils.sha256(p));
            }
        }
        return map;
    }

    /** Computes a single deterministic SHA-256 fingerprint of a world file map. */
    public static String fingerprintWorld(Map<String, String> worldFiles) {
        MessageDigest md = FileUtils.sha256();
        for (Map.Entry<String, String> e : worldFiles.entrySet()) {
            md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Compresses a single file with Zstd into {@code targetZst}. */
    public static long compressSingleFileZst(Path sourceFile, Path targetZst) throws IOException {
        Files.createDirectories(targetZst.toAbsolutePath().getParent());
        try (InputStream in = new BufferedInputStream(Files.newInputStream(sourceFile), 1 << 16);
             OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(targetZst), 1 << 16);
             ZstdOutputStream zstdOut = new ZstdOutputStream(fileOut, 3)) {
            zstdOut.setChecksum(true);
            in.transferTo(zstdOut);
        }
        return Files.size(targetZst);
    }

    /** Decompresses a single Zstd-compressed file into {@code targetFile} and verifies its SHA-256. */
    public static void decompressSingleFileZst(Path sourceZst, Path targetFile, String expectedSha256) throws IOException {
        Files.createDirectories(targetFile.toAbsolutePath().getParent());
        Path tmp = targetFile.resolveSibling(targetFile.getFileName() + ".part");
        MessageDigest md = FileUtils.sha256();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(sourceZst), 1 << 16);
             ZstdInputStream zstdIn = new ZstdInputStream(fileIn);
             OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, md)) {
            zstdIn.transferTo(digestOut);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String actualSha = HexFormat.of().formatHex(md.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha)) {
            Files.deleteIfExists(tmp);
            throw new IOException("Corrupted world blob for " + targetFile.getFileName()
                    + " (expected " + expectedSha256 + ", got " + actualSha + ")");
        }
        FileUtils.moveWithRetry(tmp, targetFile);
    }

    // ==========================================================================================
    // Streaming Tar+Zstd Archive format (used for local safety backup & S3 full snapshots)
    // ==========================================================================================

    public static ArchiveResult archive(Path sourceDir, Path target) throws IOException {
        LevelDatCleaner.cleanWorldDir(sourceDir);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        MessageDigest md = FileUtils.sha256();
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, md);
             ZstdOutputStream zstdOut = new ZstdOutputStream(digestOut, 3);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(zstdOut, "UTF-8")) {
            zstdOut.setChecksum(true);
            zstdOut.setWorkers(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);

            if (Files.isDirectory(sourceDir)) {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (!dir.equals(sourceDir)) {
                            TarArchiveEntry e = new TarArchiveEntry(FileUtils.toPortable(sourceDir.relativize(dir)) + "/");
                            e.setModTime(attrs.lastModifiedTime().toMillis());
                            tar.putArchiveEntry(e);
                            tar.closeArchiveEntry();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile() || EXCLUDED_FILES.contains(file.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        TarArchiveEntry e = new TarArchiveEntry(FileUtils.toPortable(sourceDir.relativize(file)));
                        e.setSize(attrs.size());
                        e.setModTime(attrs.lastModifiedTime().toMillis());
                        tar.putArchiveEntry(e);
                        try (InputStream in = Files.newInputStream(file)) {
                            long copied = in.transferTo(tar);
                            if (copied != attrs.size()) {
                                throw new IOException("File changed while archiving: " + file);
                            }
                        }
                        tar.closeArchiveEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            tar.finish();
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        FileUtils.moveWithRetry(tmp, target);
        return new ArchiveResult(target, HexFormat.of().formatHex(md.digest()), Files.size(target));
    }

    /** Extracts a tar.zst archive into {@code targetDir}, rejecting path traversal entries. */
    public static void extract(Path archive, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(archive), 1 << 16);
             ZstdInputStream zstdIn = new ZstdInputStream(fileIn);
             TarArchiveInputStream tar = new TarArchiveInputStream(zstdIn, "UTF-8")) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (name.isEmpty() || name.equals("./")) continue;
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root) || name.contains(":")) {
                    throw new IOException("Unsafe entry in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (entry.isFile()) {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                        tar.transferTo(os);
                    }
                    Files.setLastModifiedTime(out, FileTime.fromMillis(entry.getModTime().getTime()));
                }
            }
        }
    }

    public static String sha256(Path file) throws IOException {
        return FileUtils.sha256(file);
    }

    // ==========================================================================================
    // Remote manifest
    // ==========================================================================================

    public Optional<WorldManifest> fetchRemoteManifest() throws IOException {
        Optional<S3Storage.Fetched> f = storage.get(MANIFEST_KEY);
        if (f.isEmpty()) return Optional.empty();
        return Optional.of(Json.fromBytes(f.get().data(), WorldManifest.class));
    }

    // ==========================================================================================
    // Pull (Differential per-file when worldFiles is present, full archive fallback otherwise)
    // ==========================================================================================

    public void pullWorld(WorldManifest manifest) throws IOException {
        if (manifest.worldFiles() != null && !manifest.worldFiles().isEmpty()) {
            pullWorldDifferential(manifest);
            return;
        }
        // Fallback for legacy / S3 full-archive manifests
        Path work = cfg.stateDir().resolve("download");
        Files.createDirectories(work);
        Path archive = work.resolve("world_latest.tar.zst");
        String key = manifest.latestKey() != null ? manifest.latestKey() : LATEST_KEY;

        log.info("Downloading world v{} ({}) ...", manifest.version(), FileUtils.humanSize(manifest.sizeBytes()));
        storage.downloadFile(key, archive);
        String sha = sha256(archive);
        if (manifest.sha256() != null && !manifest.sha256().equalsIgnoreCase(sha)) {
            if (manifest.backupKey() != null) {
                log.warn("Checksum mismatch on {}, retrying from {}", key, manifest.backupKey());
                storage.downloadFile(manifest.backupKey(), archive);
                sha = sha256(archive);
            }
            if (!manifest.sha256().equalsIgnoreCase(sha)) {
                Files.deleteIfExists(archive);
                throw new IOException("World archive integrity check failed (expected " + manifest.sha256()
                        + ", got " + sha + ")");
            }
        }
        replaceLocalWorld(archive);
        Files.deleteIfExists(archive);
        LevelDatCleaner.cleanWorldDir(cfg.worldDir());
        state.update(s -> s.withWorld(manifest.version(), manifest.sha256(), Instant.now().getEpochSecond()));
        log.info("Local world is now at v{}", manifest.version());
    }

    /**
     * Option 1 Differential Pull: compares {@code manifest.worldFiles()} with the local world folder
     * and downloads/extracts <b>ONLY the files modified since our local version</b>.
     */
    private void pullWorldDifferential(WorldManifest manifest) throws IOException {
        Path world = cfg.worldDir();
        Files.createDirectories(world);

        if (hasContent(world)) {
            Path backup = cfg.serverDir().resolve(".local_backup").resolve("world_pre_sync.tar.zst");
            log.info("Salvataggio backup locale di sicurezza in {}", backup);
            archive(world, backup);
        }

        Map<String, String> localFiles = scanWorldFiles(world);
        Map<String, String> remoteFiles = manifest.worldFiles();

        List<Map.Entry<String, String>> toDownload = new ArrayList<>();
        for (Map.Entry<String, String> e : remoteFiles.entrySet()) {
            String localHash = localFiles.get(e.getKey());
            if (localHash == null || !localHash.equalsIgnoreCase(e.getValue())) {
                toDownload.add(e);
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String localPath : localFiles.keySet()) {
            if (!remoteFiles.containsKey(localPath)) {
                toDelete.add(localPath);
            }
        }

        log.info("Sync differenziale mondo v{}: {} file invariati su disco, {} file da aggiornare, {} file rimossi",
                manifest.version(),
                remoteFiles.size() - toDownload.size(),
                toDownload.size(),
                toDelete.size());

        Path dlTmp = cfg.stateDir().resolve("download_blobs");
        Files.createDirectories(dlTmp);

        for (Map.Entry<String, String> e : toDownload) {
            String relPath = e.getKey();
            String hash = e.getValue();
            String blobKey = WORLD_BLOBS_PREFIX + hash + ".zst";
            Path targetFile = world.resolve(relPath).normalize();
            if (!targetFile.startsWith(world)) {
                throw new IOException("Unsafe world file path in manifest: " + relPath);
            }
            if (storage.isGitBackend()) {
                Path zstInRepo = storage.git().localFile(blobKey);
                decompressSingleFileZst(zstInRepo, targetFile, hash);
            } else {
                Path tmpZst = dlTmp.resolve(hash + ".zst");
                storage.downloadFile(blobKey, tmpZst);
                decompressSingleFileZst(tmpZst, targetFile, hash);
                Files.deleteIfExists(tmpZst);
            }
        }

        for (String relPath : toDelete) {
            Path p = world.resolve(relPath).normalize();
            if (p.startsWith(world)) {
                FileUtils.deleteWithRetry(p);
            }
        }
        FileUtils.deleteRecursively(dlTmp);
        LevelDatCleaner.cleanWorldDir(world);

        state.update(s -> s.withWorld(manifest.version(), manifest.sha256(), Instant.now().getEpochSecond()));
        log.info("Mondo locale aggiornato alla v{} (applicati solo i {} file modificati!)",
                manifest.version(), toDownload.size());
    }

    private void replaceLocalWorld(Path archive) throws IOException {
        Path world = cfg.worldDir();
        Path extractDir = cfg.stateDir().resolve("extract_tmp");
        Path oldDir = cfg.stateDir().resolve("world_old");
        FileUtils.deleteRecursively(extractDir);
        FileUtils.deleteRecursively(oldDir);

        if (Files.isDirectory(world) && hasContent(world)) {
            Path backup = cfg.serverDir().resolve(".local_backup").resolve("world_pre_sync.tar.zst");
            log.info("Backing up current local world to {}", backup);
            archive(world, backup);
        }

        log.info("Extracting world ...");
        extract(archive, extractDir);

        if (Files.exists(world)) FileUtils.moveWithRetry(world, oldDir);
        try {
            FileUtils.moveWithRetry(extractDir, world);
        } catch (IOException e) {
            if (Files.exists(oldDir) && !Files.exists(world)) FileUtils.moveWithRetry(oldDir, world);
            throw e;
        }
        FileUtils.deleteRecursively(oldDir);
    }

    private static boolean hasContent(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        }
    }

    // ==========================================================================================
    // Push (Option 1 Differential Per-File Push on Git; Full + Differential on S3)
    // ==========================================================================================

    public PushResult pushWorld(String sessionId, boolean cleanShutdown) throws IOException {
        if (!Files.isDirectory(cfg.worldDir())) {
            throw new IOException("World folder not found: " + cfg.worldDir());
        }
        if (storage.isGitBackend()) {
            return pushWorldDifferentialGit(sessionId, cleanShutdown);
        }
        return pushWorldS3(sessionId, cleanShutdown);
    }

    /**
     * Option 1 Push for Private Git Repository:
     * <ul>
     *     <li>Scans the local world files and compares them against the previous {@code manifest.json}.</li>
     *     <li>Compresses with Zstd <b>ONLY the files created or modified in this session</b>.</li>
     *     <li>Removes superseded blobs from {@code world_blobs/} so the repository never accumulates old data.</li>
     *     <li>Commits and pushes everything in a <b>single parentless commit</b> on {@code mcsync-state}.</li>
     * </ul>
     */
    private PushResult pushWorldDifferentialGit(String sessionId, boolean cleanShutdown) throws IOException {
        Optional<WorldManifest> prevOpt = fetchRemoteManifest();
        long prevVersion = prevOpt.map(WorldManifest::version).orElse(0L);
        long version = Math.max(prevVersion, state.get().worldVersion()) + 1;
        long now = Instant.now().getEpochSecond();

        Map<String, String> prevFiles = prevOpt.map(WorldManifest::worldFiles).orElse(Map.of());
        Map<String, String> currentFiles = scanWorldFiles(cfg.worldDir());
        String worldFingerprint = fingerprintWorld(currentFiles);

        // Identify existing blobs on remote so we only compress & stage new/changed hashes
        Set<String> existingRemoteBlobs = new HashSet<>();
        for (S3Storage.Entry e : storage.list(WORLD_BLOBS_PREFIX)) {
            existingRemoteBlobs.add(e.key());
        }

        Path stageDir = cfg.stateDir().resolve("upload_blobs");
        FileUtils.deleteRecursively(stageDir);
        Files.createDirectories(stageDir);

        Map<String, Path> newBlobsToStage = new LinkedHashMap<>();
        long deltaCompressedBytes = 0;
        int changedFilesCount = 0;

        for (Map.Entry<String, String> entry : currentFiles.entrySet()) {
            String relPath = entry.getKey();
            String sha = entry.getValue();
            String prevSha = prevFiles.get(relPath);
            if (!sha.equalsIgnoreCase(prevSha)) {
                changedFilesCount++;
            }
            String blobKey = WORLD_BLOBS_PREFIX + sha + ".zst";
            if (!existingRemoteBlobs.contains(blobKey) && !newBlobsToStage.containsKey(blobKey)) {
                Path zstFile = stageDir.resolve(sha + ".zst");
                long sz = compressSingleFileZst(cfg.worldDir().resolve(relPath), zstFile);
                newBlobsToStage.put(blobKey, zstFile);
                deltaCompressedBytes += sz;
            }
        }

        // Find deleted files and unreferenced blobs to remove from the Git tree
        List<String> deletedFiles = new ArrayList<>();
        for (String oldPath : prevFiles.keySet()) {
            if (!currentFiles.containsKey(oldPath)) {
                deletedFiles.add(oldPath);
            }
        }
        Set<String> activeBlobKeys = new HashSet<>();
        for (String sha : currentFiles.values()) {
            activeBlobKeys.add(WORLD_BLOBS_PREFIX + sha + ".zst");
        }
        List<String> obsoleteBlobKeys = new ArrayList<>();
        for (String existingKey : existingRemoteBlobs) {
            if (!activeBlobKeys.contains(existingKey)) {
                obsoleteBlobKeys.add(existingKey);
            }
        }

        log.info("Delta sessione v{} -> v{}: {} file modificati/nuovi ({}), {} file eliminati, {} file totali nel mondo",
                prevVersion, version, changedFilesCount, FileUtils.humanSize(deltaCompressedBytes),
                deletedFiles.size(), currentFiles.size());

        WorldManifest manifest = new WorldManifest(
                WorldManifest.SCHEMA_VERSION,
                version,
                now,
                worldFingerprint,
                deltaCompressedBytes,
                null,
                null,
                cfg.playerId(),
                sessionId,
                cleanShutdown,
                cfg.worldFolderName(),
                prevVersion,
                WORLD_BLOBS_PREFIX,
                worldFingerprint,
                deltaCompressedBytes,
                deletedFiles,
                currentFiles
        );

        storage.git().commitSessionDeltaBatch(
                newBlobsToStage,
                obsoleteBlobKeys,
                MANIFEST_KEY,
                Json.toBytes(manifest),
                "mcsync: world v" + version + " by " + cfg.playerId()
                        + " (+" + changedFilesCount + " modified files, " + FileUtils.humanSize(deltaCompressedBytes) + ")"
        );

        FileUtils.deleteRecursively(stageDir);
        state.update(s -> s.withWorld(version, worldFingerprint, now));
        log.info("Delta v{} inviato su Git con successo ({} trasferiti)", version, FileUtils.humanSize(deltaCompressedBytes));
        return new PushResult(manifest, deltaCompressedBytes);
    }

    private PushResult pushWorldS3(String sessionId, boolean cleanShutdown) throws IOException {
        long previousVersion = fetchRemoteManifest().map(WorldManifest::version).orElse(0L);
        long version = Math.max(previousVersion, state.get().worldVersion()) + 1;
        long now = Instant.now().getEpochSecond();

        log.info("Compressing world (tar.zst) ...");
        ArchiveResult ar = archive(cfg.worldDir(), cfg.stateDir().resolve("upload").resolve("world.tar.zst"));
        log.info("Archive ready: {} (sha256 {})", FileUtils.humanSize(ar.sizeBytes()), ar.sha256().substring(0, 16));

        String backupKey = BACKUP_PREFIX + "world_v" + version + "_" + now + ".tar.zst";
        log.info("Uploading {} ...", backupKey);
        storage.uploadFile(backupKey, ar.file(), ARCHIVE_TYPE);
        try {
            storage.copy(backupKey, LATEST_KEY);
        } catch (RuntimeException e) {
            log.info("Server-side copy unavailable ({}); uploading {} directly", e.getMessage(), LATEST_KEY);
            storage.uploadFile(LATEST_KEY, ar.file(), ARCHIVE_TYPE);
        }

        WorldManifest manifest = new WorldManifest(WorldManifest.SCHEMA_VERSION, version, now, ar.sha256(),
                ar.sizeBytes(), LATEST_KEY, backupKey, cfg.playerId(), sessionId, cleanShutdown, cfg.worldFolderName());
        storage.put(MANIFEST_KEY, Json.toBytes(manifest), "application/json");
        state.update(s -> s.withWorld(version, ar.sha256(), now));
        log.info("World v{} committed", version);

        try {
            pruneBackups();
        } catch (RuntimeException e) {
            log.warn("Backup pruning failed: {}", e.getMessage());
        }
        Files.deleteIfExists(ar.file());
        return new PushResult(manifest, ar.sizeBytes());
    }

    /** Saves a conflict copy locally (on Git) or to {@code conflicts/} (on S3) without touching the manifest. */
    public String pushConflictCopy() throws IOException {
        long now = Instant.now().getEpochSecond();
        Path localConflict = cfg.serverDir().resolve(".local_backup")
                .resolve("world_conflict_" + cfg.playerId() + "_" + now + ".tar.zst");
        ArchiveResult ar = archive(cfg.worldDir(), localConflict);
        if (storage.isGitBackend()) {
            return localConflict.toString();
        }
        String key = CONFLICT_PREFIX + "world_" + cfg.playerId() + "_" + now + ".tar.zst";
        storage.uploadFile(key, ar.file(), ARCHIVE_TYPE);
        return key;
    }

    // ==========================================================================================
    // Backups
    // ==========================================================================================

    public record Backup(String key, long version, long timestamp, long size) {
    }

    public List<Backup> listBackups() {
        List<Backup> out = new ArrayList<>();
        for (S3Storage.Entry e : storage.list(BACKUP_PREFIX)) {
            Matcher m = BACKUP_NAME.matcher(e.key());
            if (m.matches()) {
                out.add(new Backup(e.key(), Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), e.size()));
            } else if (e.key().endsWith(".tar.zst")) {
                long ts = e.lastModified() != null ? e.lastModified().getEpochSecond() : 0;
                out.add(new Backup(e.key(), 0, ts, e.size()));
            }
        }
        out.sort(Comparator.comparingLong(Backup::timestamp).thenComparingLong(Backup::version));
        return out;
    }

    public void pruneBackups() {
        List<Backup> backups = listBackups();
        int excess = backups.size() - cfg.maxBackupsToKeep();
        if (excess <= 0) return;
        List<String> victims = backups.subList(0, excess).stream().map(Backup::key).toList();
        log.info("Pruning {} old backup(s): {}", victims.size(), victims);
        storage.deleteAll(victims);
    }

    public WorldManifest restoreBackup(String backupKey, String sessionId) throws IOException {
        if (!storage.exists(backupKey)) throw new IOException("Backup not found: " + backupKey);
        Path tmp = cfg.stateDir().resolve("download").resolve("restore.tar.zst");
        storage.downloadFile(backupKey, tmp);
        String sha = sha256(tmp);
        long size = Files.size(tmp);
        Files.deleteIfExists(tmp);
        storage.copy(backupKey, LATEST_KEY);
        long version = fetchRemoteManifest().map(WorldManifest::version).orElse(0L) + 1;
        WorldManifest m = new WorldManifest(WorldManifest.SCHEMA_VERSION, version, Instant.now().getEpochSecond(),
                sha, size, LATEST_KEY, backupKey, cfg.playerId(), sessionId, true, cfg.worldFolderName());
        storage.put(MANIFEST_KEY, Json.toBytes(m), "application/json");
        return m;
    }
}
