package com.mcsync.world;

import com.mcsync.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/** Minimal HTTPS helper (JDK client, redirects followed, https only). */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {
    }

    private static void requireHttps(String url) throws IOException {
        URI u = URI.create(url);
        boolean loopback = "localhost".equalsIgnoreCase(u.getHost()) || "127.0.0.1".equals(u.getHost());
        if (!"https".equalsIgnoreCase(u.getScheme()) && !(loopback && "http".equalsIgnoreCase(u.getScheme()))) {
            throw new IOException("Only https downloads are allowed: " + url);
        }
    }

    public static String getString(String url, Map<String, String> headers) throws IOException {
        requireHttps(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "mc-sync-launcher").GET();
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) return null;
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Sends an HTTPS POST with a JSON body and returns the response string (or null on 404). */
    public static String postJson(String url, Map<String, String> headers, String jsonBody) throws IOException {
        requireHttps(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "mc-sync-launcher")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) return null;
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for POST " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /** Downloads {@code url} to {@code target}; returns the SHA-256 of the downloaded bytes. */
    public static String download(String url, Map<String, String> headers, Path target) throws IOException {
        requireHttps(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "mc-sync-launcher").GET();
        headers.forEach(b::header);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() / 100 != 2) {
                r.body().close();
                throw new IOException("HTTP " + r.statusCode() + " for " + url);
            }
            MessageDigest md = FileUtils.sha256();
            try (InputStream in = new DigestInputStream(r.body(), md)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return HexFormat.of().formatHex(md.digest());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
