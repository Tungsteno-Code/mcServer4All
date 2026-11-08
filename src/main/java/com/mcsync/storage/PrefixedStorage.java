package com.mcsync.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Wrapper around {@link S3Storage} / {@link GitStorage} that transparently scopes every key under
 * a world prefix (typically {@code worlds/<worldId>/}) while keeping shared content-addressed blobs
 * accessible.
 *
 * <p>{@code LockManager} and {@code StorageSyncManager} keep using relative keys ({@code lock.json},
 * {@code manifest.json}, {@code backups/}) so the lock of one world never blocks another world on
 * the same repository/branch.</p>
 */
public final class PrefixedStorage implements AutoCloseable {

    private final S3Storage delegate;
    private final String prefix;
    private final boolean closeDelegate;

    public PrefixedStorage(S3Storage delegate, String prefix) {
        this(delegate, prefix, false);
    }

    public PrefixedStorage(S3Storage delegate, String prefix, boolean closeDelegate) {
        this.delegate = delegate;
        this.prefix = normalise(prefix);
        this.closeDelegate = closeDelegate;
    }

    /** Creates a {@link PrefixedStorage} scoped to {@code worlds/<worldId>/}. */
    public static PrefixedStorage forWorld(S3Storage rootStorage, String worldId) {
        return new PrefixedStorage(rootStorage, "worlds/" + worldId + "/", false);
    }

    public S3Storage delegate() {
        return delegate;
    }

    public String prefix() {
        return prefix;
    }

    public String qualify(String relativeKey) {
        String k = relativeKey == null ? "" : relativeKey.replace('\\', '/');
        while (k.startsWith("/")) k = k.substring(1);
        return prefix + k;
    }

    public Optional<S3Storage.Fetched> get(String key) {
        return delegate.get(qualify(key));
    }

    public String put(String key, byte[] data, String contentType) {
        return delegate.put(qualify(key), data, contentType);
    }

    public Optional<String> putIfAbsent(String key, byte[] data, String contentType) {
        return delegate.putIfAbsent(qualify(key), data, contentType);
    }

    public Optional<String> putIfMatch(String key, byte[] data, String contentType, String eTag) {
        return delegate.putIfMatch(qualify(key), data, contentType, eTag);
    }

    public void delete(String key) {
        delegate.delete(qualify(key));
    }

    public boolean exists(String key) {
        return delegate.exists(qualify(key));
    }

    public void uploadFile(String key, Path file, String contentType) throws IOException {
        delegate.uploadFile(qualify(key), file, contentType);
    }

    public void downloadFile(String key, Path target) throws IOException {
        delegate.downloadFile(qualify(key), target);
    }

    public void copy(String sourceKey, String destinationKey) {
        delegate.copy(qualify(sourceKey), qualify(destinationKey));
    }

    public List<S3Storage.Entry> list(String keyPrefix) {
        String sub = keyPrefix == null ? "" : keyPrefix;
        return delegate.list(qualify(sub)).stream()
                .map(e -> new S3Storage.Entry(stripPrefix(e.key()), e.size(), e.lastModified()))
                .toList();
    }

    public void deleteAll(List<String> keys) {
        delegate.deleteAll(keys.stream().map(this::qualify).toList());
    }

    public void commitSessionDeltaBatch(
            Map<String, Path> filesToStage,
            List<String> keysToDelete,
            String manifestKey,
            byte[] manifestBytes,
            String commitMessage) throws IOException {
        Map<String, Path> qualifiedStage = new LinkedHashMap<>();
        filesToStage.forEach((k, v) -> qualifiedStage.put(qualify(k), v));
        List<String> qualifiedDeletes = keysToDelete.stream().map(this::qualify).toList();
        delegate.git().commitSessionDeltaBatch(
                qualifiedStage,
                qualifiedDeletes,
                qualify(manifestKey),
                manifestBytes,
                commitMessage);
    }

    private String stripPrefix(String key) {
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private static String normalise(String p) {
        if (p == null || p.isBlank()) return "";
        String norm = p.trim().replace('\\', '/');
        while (norm.startsWith("/")) norm = norm.substring(1);
        return norm.endsWith("/") ? norm : norm + "/";
    }

    @Override
    public void close() {
        if (closeDelegate) delegate.close();
    }
}
