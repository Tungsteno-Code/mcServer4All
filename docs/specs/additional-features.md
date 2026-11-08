# Extended Feature Specifications: `mc-sync-launcher`

This document details the optional and high-availability features extending the core distributed locking and world-sync orchestrator.

---

## 1. Player-Aware Host Migration & Deferred Shutdown

### Objective
Prevent kicking connected peers when the host player closes their Minecraft client.

### Architecture & Logic
1. **Client Disconnect Detection:**
   * The local Minecraft client subprocess terminates.
   * `ServerProcessController` intercepts client exit and transitions into `DAEMON_HOSTING` mode rather than killing the server process immediately.
2. **Active Player Polling:**
   * Run an asynchronous monitor checking active player counts every 15 seconds.
   * Methods:
     * **Log Parsing:** Listen on server `stdout` for regex patterns: `^\[.*\]: ([a-zA-Z0-9_]{2,16}) (joined|left) the game$`. Maintain an in-memory `Set<String> onlinePlayers`.
     * **RCON / Stdin Command:** Alternatively, send `list\n` to `stdin` and parse the response `There are X of a max of Y players online: ...`.
3. **Graceful Auto-Shutdown:**
   * If `onlinePlayers.isEmpty()`, start a 60-second idle countdown timer.
   * If no players reconnect within the countdown window:
     * Broadcast to in-game chat: `[Server] No active players detected. Shutting down and syncing...`
     * Execute `stopGracefully()`.
     * Push world archive to S3 and release lock.

---

## 2. Mod & Configuration Synchronisation (`ModsSyncManager`)

### Objective
Eliminate "Registry Mismatch" and connection errors caused by desynchronised mod jars, configs, or datapacks between players.

### Architecture & Logic
1. **Manifest Generation:**
   * Scan local `mods/` and `config/` directories recursively.
   * Compute a SHA-256 hash for each `.jar`, `.json`, `.toml`, `.cfg`, and `.snbt` file.
   * Serialize into `mods_manifest.json`:
     ```json
     {
       "schemaVersion": 1,
       "lastUpdated": 1700000000,
       "files": {
         "mods/create-1.20.1-0.5.1.f.jar": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
         "config/create-server.toml": "f2ca1bb6c7e907d06dafe4687e579fce76b37e4e93b7605022da52e6ccc26fd2"
       }
     }
     ```
2. **Integrity Check on Launch:**
   * Compare local manifest against remote `mods_manifest.json` on S3.
   * **Differential Sync Mode:**
     * Upload missing/updated configs or mods from the host.
     * Download updated mods and delete obsolete `.jar` files on the client side before starting the game.
   * **Strict Check Mode (Alternative):**
     * If hashes do not match, abort launch with a detailed terminal diff showing mismatched filenames and checksums.

---

## 3. Crash Recovery & Stale Lock Remediation

### Objective
Handle unexpected crashes, power outages, or blue screens on the host PC without permanently deadlocking the server.

### Architecture & Logic
1. **Heartbeat Protocol:**
   * The host updates `lock.json` every `heartbeatIntervalSeconds` (default: 30s) containing:
     ```json
     {
       "ownerId": "player_alpha",
       "hostIp": "100.64.0.1",
       "lastHeartbeat": 1700000030,
       "sessionId": "uuid-v4"
     }
     ```
2. **Stale Evaluation:**
   * If `Instant.now().getEpochSecond() - lock.lastHeartbeat > lockTimeoutSeconds` (e.g. 120s):
     * Probe `lock.hostIp:25565` via raw TCP `Socket` with a 3000ms timeout.
     * If the socket does not respond, the lock is classified as **STALE**.
3. **Disambiguation & Takeover:**
   * Inspect the remote S3 `manifest.json` timestamp versus the local backup timestamp.
   * If the crashed host managed to push a partial/dirty world snapshot, download the latest remote snapshot.
   * Log an alert, force-release the stale lock using an S3 `DeleteObjectRequest`, acquire a new atomic lock, and proceed with standard boot.

---

## 4. Rolling Backups & Retention Policy

### Objective
Prevent world corruption (e.g. mod conflict, chunk errors) from destroying progress.

### Architecture & Logic
1. **Archive Lifecycle:**
   * When pushing the world, the synchroniser creates:
     * S3 object: `world_latest.tar.zst` (always points to the current active save).
     * S3 archive: `backups/world_v{version}_{timestamp}.tar.zst`.
2. **Storage Pruning:**
   * List all objects with prefix `backups/`.
   * Sort by timestamp ascending.
   * When total backups exceed `config.maxBackupsToKeep` (default: 5):
     * Delete the oldest archives via batch S3 `DeleteObjectsRequest`.
3. **Local Safety Fallback:**
   * Prior to extracting a newly pulled remote world archive over the local directory, archive the existing local directory to `.local_backup/world_pre_sync.tar.zst`.

---

## 5. Rich Discord Webhook Notifications

### Objective
Provide real-time visibility into server lifecycle events on a shared Discord channel.

### Payload Specification
Send JSON payloads formatted as Discord Embeds via standard HTTP POST:

* **Event: Server Booting (Host Mode)**
  * **Colour:** `0x57F287` (Green)
  * **Title:** `Minecraft Server Online`
  * **Fields:**
    * `Host:` `<playerId>`
    * `Direct IP:` `<localTailscaleIp>:25565`
    * `World Version:` `v{version}`
* **Event: Client Joined (Client Mode)**
  * **Colour:** `0x5865F2` (Blurple)
  * **Title:** `Player Connecting`
  * **Fields:**
    * `Player:` `<playerId>`
    * `Connecting To:` `<hostIp>`
* **Event: Server Shutdown & Sync**
  * **Colour:** `0xFEE75C` (Yellow)
  * **Title:** `World Synced & Server Available`
  * **Fields:**
    * `Duration:` `{hours}h {minutes}m`
    * `Archive Size:` `{sizeInMB} MB`
    * `Status:` `Lock released. Any player may host.`
* **Event: Critical Stale Lock Break**
  * **Colour:** `0xED4245` (Red)
  * **Title:** `Warning: Stale Lock Overridden`
  * **Description:** `Host <crashedHostId> stopped heartbeats. Lock was broken by <newHostId>.`

---

## 6. JVM & Server Process Watchdog

### Objective
Detect frozen servers, endless tick loops, or out-of-memory errors.

### Implementation
1. **Standard In/Out Pipe Monitoring:**
   * Attach an asynchronous line reader to process `stdout`.
   * If `stdout` contains `java.lang.OutOfMemoryError`, initiate an emergency memory-dump recovery:
     * Issue `stop\n` to `stdin`.
     * Wait 10 seconds. If process does not exit, call `Process.destroyForcibly()`.
2. **Tick Watchdog:**
   * Read `stdout` for vanilla/modded server watchdog alerts: `A single server tick took 60.00 seconds (should be max 0.05)`.
   * Trigger an alert via Discord webhook warning players of critical server lag.