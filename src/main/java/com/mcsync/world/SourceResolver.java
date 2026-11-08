package com.mcsync.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.mcsync.model.ModsLock;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Finds a public download source (Modrinth / CurseForge) for a jar file. */
public interface SourceResolver {

    /** @return a public source or empty when the jar must be stored as a {@code blob}. */
    Optional<ModsLock.Source> resolve(Path jar, String sha1, String sha256);

    /**
     * Optional batch pre-resolution over a map of {@code relPath -> jarPath} before individual lookups.
     */
    default Map<String, ModsLock.Source> resolveBatch(Map<String, Path> jars) {
        return Map.of();
    }

    /** Never resolves: every jar becomes a blob (offline mode and tests). */
    SourceResolver NONE = (jar, sha1, sha256) -> Optional.empty();

    /**
     * Combined resolver: first batch-resolves via CurseForge Murmur2 fingerprints (`POST /v1/fingerprints`),
     * then falls back to Modrinth SHA-1 lookup.
     */
    static SourceResolver combined(String curseforgeApiKey) {
        if (curseforgeApiKey == null || curseforgeApiKey.isBlank()) {
            return new Modrinth();
        }
        return new Combined(new CurseForgeFingerprint(curseforgeApiKey), new Modrinth());
    }

    /**
     * CurseForge Murmur2 fingerprint batch resolver (`POST /v1/fingerprints/432`).
     */
    final class CurseForgeFingerprint implements SourceResolver {
        private static final Logger log = LoggerFactory.getLogger(CurseForgeFingerprint.class);
        private final String apiKey;
        private final String apiBase;

        public CurseForgeFingerprint(String apiKey) {
            this(apiKey, "https://api.curseforge.com/v1");
        }

        public CurseForgeFingerprint(String apiKey, String apiBase) {
            this.apiKey = apiKey == null ? "" : apiKey;
            this.apiBase = apiBase;
        }

        @Override
        public Optional<ModsLock.Source> resolve(Path jar, String sha1, String sha256) {
            if (apiKey.isBlank()) return Optional.empty();
            Map<String, ModsLock.Source> batch = resolveBatch(Map.of("jar", jar));
            return Optional.ofNullable(batch.get("jar"));
        }

        @Override
        public Map<String, ModsLock.Source> resolveBatch(Map<String, Path> jars) {
            if (apiKey.isBlank() || jars.isEmpty()) return Map.of();
            Map<Long, String> fpToRel = new java.util.HashMap<>();
            Map<String, Path> relToPath = new java.util.HashMap<>(jars);
            for (Map.Entry<String, Path> e : jars.entrySet()) {
                try {
                    long fp = murmur2Normalized(Files.readAllBytes(e.getValue()));
                    fpToRel.put(fp, e.getKey());
                } catch (IOException ex) {
                    log.debug("Impossibile calcolare Murmur2 per {}: {}", e.getKey(), ex.getMessage());
                }
            }
            if (fpToRel.isEmpty()) return Map.of();
            try {
                var rootNode = Json.mapper().createObjectNode();
                var arr = rootNode.putArray("fingerprints");
                fpToRel.keySet().forEach(arr::add);
                String resp = Http.postJson(apiBase + "/fingerprints/432",
                        Map.of("x-api-key", apiKey), Json.mapper().writeValueAsString(rootNode));
                if (resp == null) return Map.of();
                JsonNode matches = Json.mapper().readTree(resp).path("data").path("exactMatches");
                Map<String, ModsLock.Source> result = new java.util.HashMap<>();
                for (JsonNode m : matches) {
                    long pid = m.path("id").asLong();
                    JsonNode file = m.path("file");
                    long fid = file.path("id").asLong();
                    long fp = file.path("fileFingerprint").asLong();
                    String rel = fpToRel.get(fp);
                    if (rel == null) continue;
                    String url = file.path("downloadUrl").asText("");
                    if (url.isBlank()) {
                        String fileName = file.path("fileName").asText("");
                        url = edgeCdnFallbackUrl(fid, fileName);
                    }
                    if (url != null && url.startsWith("https://")) {
                        result.put(rel, new ModsLock.Source(ModsLock.SRC_CURSEFORGE, pid + ":" + fid, url));
                    }
                }
                return result;
            } catch (Exception e) {
                log.debug("CurseForge batch fingerprint lookup failed: {}", e.getMessage());
                return Map.of();
            }
        }

        /** Computes CurseForge's whitespace-normalized Murmur2 32-bit unsigned fingerprint. */
        public static long murmur2Normalized(byte[] data) {
            int len = 0;
            for (byte b : data) {
                if (!isWhitespace(b)) len++;
            }
            final int m = 0x5bd1e995;
            final int r = 24;
            int h = 1 ^ len;
            int k = 0;
            int shift = 0;
            for (byte b : data) {
                if (isWhitespace(b)) continue;
                k |= (b & 0xFF) << shift;
                shift += 8;
                if (shift == 32) {
                    k *= m;
                    k ^= k >>> r;
                    k *= m;
                    h *= m;
                    h ^= k;
                    k = 0;
                    shift = 0;
                }
            }
            if (shift > 0) {
                h ^= k;
                h *= m;
            }
            h ^= h >>> 13;
            h *= m;
            h ^= h >>> 15;
            return Integer.toUnsignedLong(h);
        }

        private static boolean isWhitespace(byte b) {
            return b == 9 || b == 10 || b == 13 || b == 32;
        }

        /** Constructs the secondary CurseForge edge CDN URL when downloadUrl is null in API responses. */
        public static String edgeCdnFallbackUrl(long fileId, String fileName) {
            if (fileId <= 0 || fileName == null || fileName.isBlank()) return null;
            String idStr = Long.toString(fileId);
            if (idStr.length() < 4) return null;
            String p1 = idStr.substring(0, 4);
            String p2 = idStr.substring(4).replaceFirst("^0+(?!$)", "");
            if (p2.isEmpty()) p2 = "0";
            String encodedName = fileName.replace(" ", "%20").replace("+", "%2B");
            return "https://edge.forgecdn.net/files/" + p1 + "/" + p2 + "/" + encodedName;
        }
    }

    final class Combined implements SourceResolver {
        private final SourceResolver primary;
        private final SourceResolver fallback;

        public Combined(SourceResolver primary, SourceResolver fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public Map<String, ModsLock.Source> resolveBatch(Map<String, Path> jars) {
            return primary.resolveBatch(jars);
        }

        @Override
        public Optional<ModsLock.Source> resolve(Path jar, String sha1, String sha256) {
            Optional<ModsLock.Source> r = primary.resolve(jar, sha1, sha256);
            return r.isPresent() ? r : fallback.resolve(jar, sha1, sha256);
        }
    }

    /**
     * Modrinth lookup by SHA-1 ({@code GET /v2/version_file/<sha1>?algorithm=sha1}). Anything that
     * is not found, malformed or unreachable simply falls back to the blob store.
     */
    final class Modrinth implements SourceResolver {
        private static final Logger log = LoggerFactory.getLogger(Modrinth.class);
        private final String apiBase;

        public Modrinth() {
            this("https://api.modrinth.com/v2");
        }

        public Modrinth(String apiBase) {
            this.apiBase = apiBase;
        }

        @Override
        public Optional<ModsLock.Source> resolve(Path jar, String sha1, String sha256) {
            try {
                String body = Http.getString(apiBase + "/version_file/" + sha1 + "?algorithm=sha1", Map.of());
                if (body == null) return Optional.empty();
                JsonNode v = Json.mapper().readTree(body);
                for (JsonNode f : v.path("files")) {
                    if (sha1.equalsIgnoreCase(f.path("hashes").path("sha1").asText(""))) {
                        String url = f.path("url").asText("");
                        if (url.startsWith("https://")) {
                            return Optional.of(new ModsLock.Source(ModsLock.SRC_MODRINTH, v.path("id").asText(null), url));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Modrinth lookup failed for {}: {}", jar.getFileName(), e.getMessage());
            }
            return Optional.empty();
        }
    }
}
