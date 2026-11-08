package com.mcsync.storage;

import com.mcsync.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unified remote storage facade supporting two backends:
 * <ul>
 *     <li><b>GIT</b> (default when {@code storageBackend="GIT"} or {@code gitRemoteUrl} is set):
 *     uses a private GitHub / GitLab repository via {@link GitStorage} with atomic CAS pushes and
 *     single-commit history (so the Git repo never grows in size).</li>
 *     <li><b>S3</b>: uses an S3-compatible bucket (Cloudflare R2, MinIO, AWS S3, Backblaze B2).</li>
 * </ul>
 */
public final class S3Storage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3Storage.class);

    private static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final long PART_SIZE = 32L * 1024 * 1024;

    private final GitStorage git;
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    /** Object read result with its ETag / SHA (used for optimistic concurrency). */
    public record Fetched(byte[] data, String eTag) {
    }

    /** Listing entry with the key stripped of the configured prefix. */
    public record Entry(String key, long size, java.time.Instant lastModified) {
    }

    public S3Storage(Config cfg) {
        this(cfg, "git");
    }

    public S3Storage(Config cfg, String gitExecutable) {
        this.prefix = cfg.s3KeyPrefix();
        if (cfg.isGitBackend()) {
            this.git = new GitStorage(cfg, gitExecutable);
            this.s3 = null;
            this.bucket = cfg.gitRemoteUrl();
        } else {
            this.git = null;
            this.bucket = cfg.s3Bucket();
            this.s3 = S3Client.builder()
                    .endpointOverride(URI.create(cfg.s3Endpoint().trim()))
                    .region(Region.of(cfg.s3Region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(cfg.s3AccessKey(), cfg.s3SecretKey())))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(cfg.s3PathStyle())
                            .chunkedEncodingEnabled(false)
                            .build())
                    .httpClientBuilder(ApacheHttpClient.builder()
                            .connectionTimeout(Duration.ofSeconds(15))
                            .socketTimeout(Duration.ofMinutes(2))
                            .maxConnections(16))
                    .overrideConfiguration(o -> o.apiCallAttemptTimeout(Duration.ofMinutes(30)))
                    .build();
        }
    }

    public boolean isGitBackend() {
        return git != null;
    }

    public GitStorage git() {
        return git;
    }

    public String fullKey(String key) {
        return prefix + key;
    }

    // ------------------------------------------------------------------------------------------
    // Small objects
    // ------------------------------------------------------------------------------------------

    public Optional<Fetched> get(String key) {
        if (git != null) return git.get(key);
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(fullKey(key)).build());
            return Optional.of(new Fetched(bytes.asByteArray(), bytes.response().eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    public String put(String key, byte[] data, String contentType) {
        if (git != null) return git.put(key, data, contentType);
        return s3.putObject(PutObjectRequest.builder().bucket(bucket).key(fullKey(key))
                .contentType(contentType).build(), RequestBody.fromBytes(data)).eTag();
    }

    /**
     * Atomic create: {@code PutObject} with {@code If-None-Match: *} (or Git atomic CAS push).
     */
    public Optional<String> putIfAbsent(String key, byte[] data, String contentType) {
        if (git != null) return git.putIfAbsent(key, data, contentType);
        try {
            PutObjectResponse r = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(fullKey(key))
                    .contentType(contentType)
                    .overrideConfiguration(o -> o.putHeader("If-None-Match", "*"))
                    .build(), RequestBody.fromBytes(data));
            return Optional.of(r.eTag());
        } catch (S3Exception e) {
            if (isPreconditionFailure(e)) return Optional.empty();
            throw e;
        }
    }

    /**
     * Optimistic update: {@code PutObject} with {@code If-Match: <etag>} (or Git atomic CAS push).
     */
    public Optional<String> putIfMatch(String key, byte[] data, String contentType, String eTag) {
        if (git != null) return git.putIfMatch(key, data, contentType, eTag);
        try {
            PutObjectResponse r = s3.putObject(PutObjectRequest.builder().bucket(bucket).key(fullKey(key))
                    .contentType(contentType)
                    .overrideConfiguration(o -> o.putHeader("If-Match", eTag))
                    .build(), RequestBody.fromBytes(data));
            return Optional.of(r.eTag());
        } catch (S3Exception e) {
            if (isPreconditionFailure(e) || e.statusCode() == 404) return Optional.empty();
            if (e.statusCode() == 501 || e.statusCode() == 400) {
                throw new UnsupportedOperationException("Conditional If-Match PUT not supported: "
                        + e.awsErrorDetails().errorMessage(), e);
            }
            throw e;
        }
    }

    public void delete(String key) {
        if (git != null) {
            git.delete(key);
            return;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
    }

    public boolean exists(String key) {
        if (git != null) return git.exists(key);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Large objects
    // ------------------------------------------------------------------------------------------

    public void uploadFile(String key, Path file, String contentType) throws IOException {
        if (git != null) {
            git.uploadFile(key, file, contentType);
            return;
        }
        long size = Files.size(file);
        if (size <= MULTIPART_THRESHOLD) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(fullKey(key)).contentType(contentType)
                    .contentLength(size).build(), RequestBody.fromFile(file));
            return;
        }
        String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(fullKey(key)).contentType(contentType).build()).uploadId();
        List<CompletedPart> parts = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[(int) PART_SIZE];
            int partNumber = 1;
            long uploaded = 0;
            while (true) {
                int read = in.readNBytes(buffer, 0, buffer.length);
                if (read <= 0) break;
                final int pn = partNumber;
                UploadPartResponse r = s3.uploadPart(UploadPartRequest.builder().bucket(bucket)
                                .key(fullKey(key)).uploadId(uploadId).partNumber(pn).contentLength((long) read).build(),
                        RequestBody.fromBytes(read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read)));
                parts.add(CompletedPart.builder().partNumber(pn).eTag(r.eTag()).build());
                uploaded += read;
                log.info("  upload {}: {}%", key, (uploaded * 100) / size);
                partNumber++;
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(fullKey(key))
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()).build());
        } catch (IOException | SdkException e) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket)
                        .key(fullKey(key)).uploadId(uploadId).build());
            } catch (SdkException abortError) {
                e.addSuppressed(abortError);
            }
            throw e;
        }
    }

    public void downloadFile(String key, Path target) throws IOException {
        if (git != null) {
            git.downloadFile(key, target);
            return;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(fullKey(key)).build(),
                    ResponseTransformer.toFile(tmp));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void copy(String sourceKey, String destinationKey) {
        if (git != null) {
            git.copy(sourceKey, destinationKey);
            return;
        }
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(fullKey(sourceKey))
                .destinationBucket(bucket).destinationKey(fullKey(destinationKey))
                .build());
    }

    // ------------------------------------------------------------------------------------------
    // Listing / batch delete
    // ------------------------------------------------------------------------------------------

    public List<Entry> list(String keyPrefix) {
        if (git != null) return git.list(keyPrefix);
        List<Entry> out = new ArrayList<>();
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(fullKey(keyPrefix)).build());
        for (S3Object o : pages.contents()) {
            out.add(new Entry(o.key().substring(prefix.length()), o.size(), o.lastModified()));
        }
        return out;
    }

    public void deleteAll(List<String> keys) {
        if (git != null) {
            git.deleteAll(keys);
            return;
        }
        for (int i = 0; i < keys.size(); i += 1000) {
            List<ObjectIdentifier> ids = keys.subList(i, Math.min(keys.size(), i + 1000)).stream()
                    .map(k -> ObjectIdentifier.builder().key(fullKey(k)).build()).toList();
            DeleteObjectsResponse r = s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                    .delete(Delete.builder().objects(ids).quiet(true).build()).build());
            if (r.hasErrors() && !r.errors().isEmpty()) {
                r.errors().forEach(err -> log.warn("Could not delete {}: {}", err.key(), err.message()));
            }
        }
    }

    public void checkAccess() {
        if (git != null) {
            git.checkAccess();
            return;
        }
        try {
            s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1).build());
        } catch (S3Exception e) {
            throw new IllegalStateException("Cannot access bucket '" + bucket + "' (HTTP " + e.statusCode() + "): "
                    + (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        } catch (SdkException e) {
            throw new IllegalStateException("Cannot reach S3 endpoint: " + e.getMessage(), e);
        }
    }

    private static boolean isPreconditionFailure(S3Exception e) {
        return e.statusCode() == 412 || e.statusCode() == 409;
    }

    @Override
    public void close() {
        if (s3 != null) s3.close();
    }
}
