package com.mcsync.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safe zip extraction (zip-slip protected). */
public final class Zips {

    private Zips() {
    }

    /** Reads one entry fully, or {@code null} if absent. */
    public static byte[] read(Path zip, String entry) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    public static boolean hasEntry(Path zip, String entry) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.getEntry(entry) != null;
        }
    }

    public static boolean hasEntryWithPrefix(Path zip, String prefix) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.stream().anyMatch(e -> e.getName().replace('\\', '/').startsWith(prefix));
        }
    }

    /**
     * Extracts the entries under {@code prefix} (e.g. {@code overrides/}) into {@code target}, stripping
     * the prefix. An empty prefix extracts everything.
     *
     * @return number of files written
     */
    public static int extract(Path zip, String prefix, Path target) throws IOException {
        int count = 0;
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith(prefix) || e.isDirectory()) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) continue;
                Path out = root.resolve(rel).normalize();
                if (!out.startsWith(root)) throw new IOException("Unsafe zip entry: " + e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        return count;
    }

    /**
     * If the zip has a single top-level folder (very common in server packs) returns its prefix
     * ({@code folder/}), otherwise an empty string.
     */
    public static String singleTopLevelPrefix(Path zip) throws IOException {
        String top = null;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash < 0) return "";
                String first = name.substring(0, slash + 1);
                if (top == null) top = first;
                else if (!top.equals(first)) return "";
            }
        }
        return top == null ? "" : top;
    }
}
