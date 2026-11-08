package com.mcsync.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mcsync.config.Config;
import com.mcsync.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight Discord webhook dispatcher (Java 21 {@link HttpClient}, no extra dependencies).
 * Messages are sent asynchronously; {@link #flush(Duration)} waits for pending deliveries.
 */
public final class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    public static final int GREEN = 0x57F287;
    public static final int BLURPLE = 0x5865F2;
    public static final int YELLOW = 0xFEE75C;
    public static final int RED = 0xED4245;

    private final String webhookUrl;
    private final String worldLabel;
    private final HttpClient http;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public DiscordNotifier(Config cfg) {
        this.webhookUrl = cfg.discordEnabled() ? cfg.discordWebhookUrl() : null;
        this.worldLabel = cfg.activeWorld()
                .map(w -> w.displayName().equals(w.id()) ? w.id() : w.displayName() + " (" + w.id() + ")")
                .orElseGet(() -> cfg.activeWorldId() != null && !cfg.activeWorldId().isBlank()
                        ? cfg.activeWorldId() : cfg.worldFolderName());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String worldLabel() {
        return worldLabel;
    }

    // ------------------------------------------------------------------------------------------
    // Events from the specification
    // ------------------------------------------------------------------------------------------

    public void serverOnline(String host, String ip, int port, long worldVersion) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("World:", worldLabel);
        f.put("Host:", host);
        f.put("Direct IP:", ip + ":" + port);
        f.put("World Version:", "v" + worldVersion);
        send("Minecraft Server Online — " + worldLabel, null, GREEN, f);
    }

    public void clientJoined(String player, String hostIp) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("World:", worldLabel);
        f.put("Player:", player);
        f.put("Connecting To:", hostIp);
        send("Player Connecting — " + worldLabel, null, BLURPLE, f);
    }

    public void worldSynced(Duration duration, long archiveBytes) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("World:", worldLabel);
        f.put("Duration:", duration.toHours() + "h " + duration.toMinutesPart() + "m");
        f.put("Archive Size:", String.format(java.util.Locale.ROOT, "%.1f MB", archiveBytes / (1024.0 * 1024.0)));
        f.put("Status:", "Lock released. Any player may host.");
        send("World Synced & Server Available — " + worldLabel, null, YELLOW, f);
    }

    public void staleLockBroken(String crashedHostId, String newHostId) {
        send("Warning: Stale Lock Overridden — " + worldLabel,
                "Host " + crashedHostId + " stopped heartbeats on world '" + worldLabel + "'. Lock was broken by " + newHostId + ".", RED, Map.of("World:", worldLabel));
    }

    public void modpackUpdated(long modpackVersion, String updatedBy) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("World:", worldLabel);
        f.put("Modpack Version:", "v" + modpackVersion);
        f.put("Updated By:", updatedBy);
        send("Modpack Updated — " + worldLabel, null, BLURPLE, f);
    }

    // ------------------------------------------------------------------------------------------
    // Additional alerts (watchdog, lock loss...)
    // ------------------------------------------------------------------------------------------

    public void alert(String title, String description) {
        send(title, description, RED, Map.of());
    }

    public void info(String title, String description) {
        send(title, description, YELLOW, Map.of());
    }

    // ------------------------------------------------------------------------------------------

    public void send(String title, String description, int colour, Map<String, String> fields) {
        if (webhookUrl == null) return;
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("username", "mcServer4all");
        ArrayNode embeds = root.putArray("embeds");
        ObjectNode embed = embeds.addObject();
        embed.put("title", title);
        if (description != null) embed.put("description", description);
        embed.put("color", colour);
        embed.put("timestamp", Instant.now().toString());
        if (!fields.isEmpty()) {
            ArrayNode arr = embed.putArray("fields");
            fields.forEach((k, v) -> arr.addObject().put("name", k).put("value", v == null || v.isBlank() ? "-" : v).put("inline", true));
        }
        dispatch(Json.toString(root), 0);
    }

    private void dispatch(String body, int attempt) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "mc-sync-launcher (https://github.com, 1.0)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        pending.add(f);
        f.whenComplete((resp, err) -> {
            pending.remove(f);
            if (err != null) {
                log.warn("Discord webhook failed: {}", err.getMessage());
                return;
            }
            int code = resp.statusCode();
            if (code == 429 && attempt < 2) {
                long waitMs = retryAfterMillis(resp);
                CompletableFuture<Void> retry = CompletableFuture.runAsync(() -> dispatch(body, attempt + 1),
                        CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS));
                pending.add(retry);
                retry.whenComplete((v, e) -> pending.remove(retry));
            } else if (code >= 300) {
                log.warn("Discord webhook returned HTTP {}: {}", code, resp.body());
            }
        });
    }

    private static long retryAfterMillis(HttpResponse<String> resp) {
        try {
            JsonNode n = Json.mapper().readTree(resp.body());
            if (n.has("retry_after")) return (long) (n.get("retry_after").asDouble() * 1000) + 100;
        } catch (Exception ignored) {
            // fall through
        }
        return 2000;
    }

    /** Waits (bounded) for all queued webhook deliveries. */
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .get(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // timeout / failures are already logged
            }
        }
    }
}
