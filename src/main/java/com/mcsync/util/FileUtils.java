package com.mcsync.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** File-system helpers that behave correctly on Windows (read-only flags, transient locks). */
public final class FileUtils {

    public static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private FileUtils() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            in.transferTo(OutputStreamSink.INSTANCE);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Recursively deletes a directory, clearing DOS read-only flags and retrying transient locks. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteWithRetry(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                deleteWithRetry(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteWithRetry(Path p) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                clearReadOnly(p);
                Files.deleteIfExists(p);
                return;
            } catch (FileSystemException e) {
                // Windows: antivirus / indexer / a just-exited process may still hold a handle.
                last = e;
                sleepQuietly(300L * (attempt + 1));
            }
        }
        throw last;
    }

    /** Moves (renames) a file or directory, retrying transient Windows sharing violations. */
    public static void moveWithRetry(Path source, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException e) {
                last = e;
                sleepQuietly(300L * (attempt + 1));
            }
        }
        throw last;
    }

    private static void clearReadOnly(Path p) {
        if (!IS_WINDOWS) return;
        try {
            DosFileAttributeView view = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null && view.readAttributes().isReadOnly()) view.setReadOnly(false);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Converts a relative path to the portable forward-slash form used in archives and manifests. */
    public static String toPortable(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }

    public static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Null output stream used to drain digest streams. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
