package com.mcsync.core;

import com.mcsync.config.Config;
import com.mcsync.model.LockFile;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.Json;
import com.mcsync.util.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Distributed lock stored as {@code lock.json} in the bucket.
 *
 * <ul>
 *     <li>Acquisition is atomic: {@code PutObject} with {@code If-None-Match: *}.</li>
 *     <li>A daemon thread refreshes {@code lastHeartbeat} every {@code heartbeatIntervalSeconds}.</li>
 *     <li>A lock is STALE when its heartbeat is older than {@code lockTimeoutSeconds} AND a TCP
 *     probe to {@code hostIp:25565} fails within 3 s.</li>
 *     <li>Release is a {@code DeleteObject}, performed only after verifying we still own it.</li>
 * </ul>
 */
public final class LockManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LockManager.class);

    public static final String LOCK_KEY = "lock.json";
    private static final String JSON = "application/json";
    private static final int PROBE_TIMEOUT_MS = 3000;

    /** Result of {@link #inspect()}. */
    public enum State {
        /** No lock: anyone may host. */
        FREE,
        /** Another player is hosting (or starting / stopping). */
        HELD,
        /** Heartbeat expired and the host does not answer: may be broken. */
        STALE,
        /** Lock left behind by this very player (previous crash on this PC). */
        OWN_LEFTOVER
    }

    public record Inspection(State state, LockFile lock, String eTag, long heartbeatAgeSeconds) {
    }

    private final Config cfg;
    private final S3Storage storage;
    private final ScheduledExecutorService scheduler;

    private volatile LockFile current;
    private volatile String currentETag;
    private volatile boolean ifMatchSupported = true;
    private ScheduledFuture<?> heartbeatTask;

    public LockManager(Config cfg, S3Storage storage) {
        this.cfg = cfg;
        this.storage = storage;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------------------------------

    public Inspection inspect() throws IOException {
        Optional<S3Storage.Fetched> fetched = storage.get(LOCK_KEY);
        if (fetched.isEmpty()) return new Inspection(State.FREE, null, null, 0);

        LockFile lock;
        try {
            lock = Json.fromBytes(fetched.get().data(), LockFile.class);
        } catch (IOException e) {
            // A corrupted lock can never be refreshed by its owner: treat as stale.
            log.warn("lock.json is corrupted ({}); treating it as stale", e.getMessage());
            return new Inspection(State.STALE, new LockFile("unknown", "", 0, "corrupted", null, null, null),
                    fetched.get().eTag(), Long.MAX_VALUE);
        }
        long age = Instant.now().getEpochSecond() - lock.lastHeartbeat();

        if (cfg.playerId().equals(lock.ownerId())
                && (lock.hostIp() == null || lock.hostIp().equals(cfg.localTailscaleIp())
                || age > cfg.lockTimeoutSeconds())) {
            return new Inspection(State.OWN_LEFTOVER, lock, fetched.get().eTag(), age);
        }
        if (age > cfg.lockTimeoutSeconds()) {
            boolean alive = NetUtils.tcpProbe(lock.hostIp(), lock.port(), PROBE_TIMEOUT_MS);
            if (!alive) return new Inspection(State.STALE, lock, fetched.get().eTag(), age);
            log.warn("Lock heartbeat of {} is {}s old but {}:{} still answers: host considered alive",
                    lock.ownerId(), age, lock.hostIp(), lock.port());
        }
        return new Inspection(State.HELD, lock, fetched.get().eTag(), age);
    }

    // ------------------------------------------------------------------------------------------
    // Acquisition / takeover
    // ------------------------------------------------------------------------------------------

    /**
     * Tries to atomically create the lock.
     *
     * @return true if this process now owns the lock
     */
    public synchronized boolean tryAcquire() throws IOException {
        long now = Instant.now().getEpochSecond();
        LockFile lock = new LockFile(cfg.playerId(), cfg.localTailscaleIp(), now, UUID.randomUUID().toString(),
                LockFile.Status.STARTING, cfg.serverPort(), now);
        Optional<String> eTag = storage.putIfAbsent(LOCK_KEY, Json.toBytes(lock), JSON);
        if (eTag.isEmpty()) return false;

        // Defensive read-back: some S3-compatible providers silently ignore If-None-Match.
        Optional<S3Storage.Fetched> check = storage.get(LOCK_KEY);
        if (check.isEmpty()) return false;
        LockFile stored = Json.fromBytes(check.get().data(), LockFile.class);
        if (!lock.sessionId().equals(stored.sessionId())) {
            log.warn("Lost the lock race against {}", stored.ownerId());
            return false;
        }
        current = lock;
        currentETag = check.get().eTag();
        log.info("Lock acquired (session {})", lock.sessionId());
        return true;
    }

    /**
     * Breaks a stale (or own leftover) lock and acquires a new one. The lock is re-read right
     * before deletion: if it changed (the host came back, or somebody else took over) nothing
     * is deleted.
     */
    public synchronized boolean breakAndAcquire(Inspection stale) throws IOException {
        Optional<S3Storage.Fetched> again = storage.get(LOCK_KEY);
        if (again.isPresent()) {
            if (stale.eTag() != null && !stale.eTag().equals(again.get().eTag())) {
                log.warn("Lock changed while evaluating staleness; aborting takeover");
                return false;
            }
            log.warn("Force-releasing lock of '{}' (session {}, heartbeat {}s old)",
                    stale.lock().ownerId(), stale.lock().sessionId(), stale.heartbeatAgeSeconds());
            storage.delete(LOCK_KEY);
        }
        return tryAcquire();
    }

    // ------------------------------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------------------------------

    /**
     * Starts the heartbeat daemon thread.
     *
     * @param onLockLost invoked (once) if the lock disappears or is taken by someone else
     */
    public synchronized void startHeartbeat(Consumer<LockFile> onLockLost) {
        if (heartbeatTask != null) return;
        int interval = cfg.heartbeatIntervalSeconds();
        final boolean[] lostReported = {false};
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (current == null) return;
                Optional<LockFile> stolenBy = refresh(null);
                if (stolenBy != null && stolenBy.isPresent() && !lostReported[0]) {
                    lostReported[0] = true;
                    onLockLost.accept(stolenBy.get());
                } else if (stolenBy != null && stolenBy.isEmpty() && !lostReported[0]) {
                    lostReported[0] = true;
                    onLockLost.accept(null);
                }
            } catch (Exception e) {
                // Transient network errors: the timeout is several heartbeats long.
                log.warn("Heartbeat failed: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        log.info("Heartbeat started (every {}s)", interval);
    }

    /** Publishes a new lifecycle status immediately (also refreshes the heartbeat). */
    public synchronized void updateStatus(LockFile.Status status) {
        if (current == null) return;
        try {
            refresh(status);
        } catch (Exception e) {
            log.warn("Could not publish lock status {}: {}", status, e.getMessage());
        }
    }

    /**
     * Refreshes the lock.
     *
     * @return {@code null} if refreshed, {@code Optional.empty()} if the lock vanished,
     * {@code Optional.of(lock)} if somebody else owns it now
     */
    private synchronized Optional<LockFile> refresh(LockFile.Status newStatus) throws IOException {
        LockFile mine = current;
        if (mine == null) return null;
        Optional<S3Storage.Fetched> remote = storage.get(LOCK_KEY);
        if (remote.isEmpty()) {
            log.error("LOCK LOST: lock.json disappeared from the bucket");
            current = null;
            return Optional.empty();
        }
        LockFile stored = Json.fromBytes(remote.get().data(), LockFile.class);
        if (!mine.sessionId().equals(stored.sessionId())) {
            log.error("LOCK LOST: lock now owned by '{}' (session {})", stored.ownerId(), stored.sessionId());
            current = null;
            return Optional.of(stored);
        }
        LockFile next = mine.withHeartbeat(Instant.now().getEpochSecond());
        if (newStatus != null) next = next.withStatus(newStatus);
        byte[] body = Json.toBytes(next);
        String eTag = remote.get().eTag();
        if (ifMatchSupported) {
            try {
                Optional<String> newTag = storage.putIfMatch(LOCK_KEY, body, JSON, eTag);
                if (newTag.isEmpty()) {
                    log.error("LOCK LOST: lock.json modified concurrently");
                    current = null;
                    return Optional.empty();
                }
                current = next;
                currentETag = newTag.get();
                return null;
            } catch (UnsupportedOperationException e) {
                log.info("Provider does not support If-Match on PUT; using read-verify-write heartbeats");
                ifMatchSupported = false;
            }
        }
        currentETag = storage.put(LOCK_KEY, body, JSON);
        current = next;
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Release
    // ------------------------------------------------------------------------------------------

    /** Deletes the lock if (and only if) we still own it. Safe to call multiple times. */
    public synchronized void release() {
        stopHeartbeat();
        LockFile mine = current;
        if (mine == null) return;
        try {
            Optional<S3Storage.Fetched> remote = storage.get(LOCK_KEY);
            if (remote.isPresent()) {
                LockFile stored = Json.fromBytes(remote.get().data(), LockFile.class);
                if (mine.sessionId().equals(stored.sessionId())) {
                    storage.delete(LOCK_KEY);
                    log.info("Lock released");
                } else {
                    log.warn("Not releasing: lock belongs to '{}' now", stored.ownerId());
                }
            }
        } catch (Exception e) {
            log.error("Failed to release lock: {} (it will become stale after {}s)", e.getMessage(),
                    cfg.lockTimeoutSeconds());
        } finally {
            current = null;
            currentETag = null;
        }
    }

    public synchronized void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    public boolean isHeld() {
        return current != null;
    }

    public LockFile current() {
        return current;
    }

    public String sessionId() {
        LockFile c = current;
        return c != null ? c.sessionId() : null;
    }

    @Override
    public void close() {
        stopHeartbeat();
        scheduler.shutdownNow();
    }
}
