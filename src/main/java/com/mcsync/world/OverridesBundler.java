package com.mcsync.world;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mcsync.util.FileUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 * Packs and unpacks modpack {@code overrides/} directories (configs, kubejs, defaultconfigs,
 * datapacks, scripts) into compressed {@code overrides.partXX.tar.zst} volume bundles (&lt; 75 MB each)
 * so Git never has to store thousands of loose config files.
 *
 * <h3>Seed-Only Policy for Player Preferences:</h3>
 * <ul>
 *     <li>Gameplay/registry files ({@code config/**}, {@code defaultconfigs/**}, {@code kubejs/**},
 *     {@code datapacks/**}) are always extracted 1:1 to guarantee zero registry desync.</li>
 *     <li>Personal client preference files ({@code options.txt}, {@code optionsof.txt},
 *     {@code optionsshaders.txt}, {@code servers.dat}, {@code config/*-client.toml}) are extracted
 *     in <b>Seed-Only</b> mode on the client: created on first setup, never overwritten if the player
 *     already customized them.</li>
 * </ul>
 */
public final class OverridesBundler {

    private static final Logger log = LoggerFactory.getLogger(OverridesBundler.class);

    /** Max uncompressed/part threshold (~65 MB raw -> well under 75 MB compressed & GitHub's 100 MB limit). */
    public static final long MAX_PART_RAW_BYTES = 65L * 1024 * 1024;

    public record BundleResult(Map<String, String> bundleShas, Map<String, Path> blobsBySha, int fileCount, long totalBytes) {
    }

    private OverridesBundler() {
    }

    /**
     * Packs {@code files} (relativePath -&gt; source Path) into one or more {@code .tar.zst} part files
     * inside {@code workDir}.
     */
    public static BundleResult pack(Map<String, Path> files, Path workDir) throws IOException {
        if (files.isEmpty()) {
            return new BundleResult(Map.of(), Map.of(), 0, 0L);
        }
        Files.createDirectories(workDir);
        List<Map<String, Path>> parts = new ArrayList<>();
        Map<String, Path> currentPart = new LinkedHashMap<>();
        long currentBytes = 0;
        long totalBytes = 0;

        for (Map.Entry<String, Path> e : files.entrySet()) {
            long sz = Files.size(e.getValue());
            totalBytes += sz;
            if (!currentPart.isEmpty() && (currentBytes + sz) > MAX_PART_RAW_BYTES) {
                parts.add(currentPart);
                currentPart = new LinkedHashMap<>();
                currentBytes = 0;
            }
            currentPart.put(e.getKey(), e.getValue());
            currentBytes += sz;
        }
        if (!currentPart.isEmpty()) {
            parts.add(currentPart);
        }

        Map<String, String> bundleShas = new TreeMap<>();
        Map<String, Path> blobsBySha = new LinkedHashMap<>();

        for (int i = 0; i < parts.size(); i++) {
            String partName = parts.size() == 1
                    ? "overrides.tar.zst"
                    : String.format(Locale.ROOT, "overrides.part%02d.tar.zst", i + 1);
            Path archive = workDir.resolve(partName);
            writeTarZst(parts.get(i), archive);
            String sha = FileUtils.sha256(archive);
            bundleShas.put(partName, sha);
            blobsBySha.put(sha, archive);
        }
        log.info("Compressi {} file overrides ({}) in {} bundle Zstd",
                files.size(), FileUtils.humanSize(totalBytes), parts.size());
        return new BundleResult(bundleShas, blobsBySha, files.size(), totalBytes);
    }

    private static void writeTarZst(Map<String, Path> entries, Path archive) throws IOException {
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archive), 1 << 16);
             ZstdOutputStream zstdOut = new ZstdOutputStream(fileOut, 3);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(zstdOut, "UTF-8")) {
            zstdOut.setChecksum(true);
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);

            for (Map.Entry<String, Path> e : entries.entrySet()) {
                Path src = e.getValue();
                long size = Files.size(src);
                TarArchiveEntry te = new TarArchiveEntry(e.getKey());
                te.setSize(size);
                te.setModTime(Files.getLastModifiedTime(src).toMillis());
                tar.putArchiveEntry(te);
                try (InputStream in = Files.newInputStream(src)) {
                    in.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    /**
     * Extracts a {@code .tar.zst} overrides bundle into {@code targetDir}.
     *
     * @param seedClientPrefs if true, player preference files ({@code options.txt}, {@code *-client.toml})
     *                        are not overwritten if they already exist on disk.
     * @return number of files written
     */
    public static int extract(Path archive, Path targetDir, boolean seedClientPrefs) throws IOException {
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();
        int written = 0;
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(archive), 1 << 16);
             ZstdInputStream zstdIn = new ZstdInputStream(fileIn);
             TarArchiveInputStream tar = new TarArchiveInputStream(zstdIn, "UTF-8")) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String rel = entry.getName().replace('\\', '/');
                while (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.isEmpty() || rel.contains("..")) continue;
                Path out = root.resolve(rel).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe path in overrides bundle: " + rel);
                }
                if (seedClientPrefs && isPersonalPreferenceFile(rel) && Files.isRegularFile(out)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                Path tmp = out.resolveSibling(out.getFileName() + ".part");
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
                    tar.transferTo(os);
                }
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.setLastModifiedTime(out, FileTime.fromMillis(entry.getModTime().getTime()));
                } catch (IOException ignored) {
                }
                written++;
            }
        }
        return written;
    }

    /** True for personal keybind/graphics files that should be Seed-Only on clients. */
    public static boolean isPersonalPreferenceFile(String relPath) {
        String lower = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (lower.equals("options.txt") || lower.equals("optionsof.txt")
                || lower.equals("optionsshaders.txt") || lower.equals("servers.dat")
                || lower.equals("usercache.json")) {
            return true;
        }
        return lower.startsWith("config/") && lower.endsWith("-client.toml");
    }
}
