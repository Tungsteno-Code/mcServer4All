You are an expert systems software engineer. Your task is to implement a complete, production-grade Java 21 distributed orchestrator for a modded Minecraft server called `mc-sync-launcher`.

### Context & Architecture
Two players share a modded Minecraft world without a 24/7 dedicated server. When either player launches the game:
1. It checks an S3-compatible bucket (e.g. Cloudflare R2) via atomic locking.
2. If the other player is already hosting, it launches the modded client (via Prism Launcher CLI) pointing to the host's Tailscale mesh IP.
3. If free, it acquires the lock, pulls the latest world archive (`.tar.zst`), launches the local modded server bound to Tailscale, and then boots the local client to `127.0.0.1`.
4. On exit, it waits for all players to disconnect, stops the server gracefully (`save-all flush` + `stop`), compresses the world folder (`.tar.zst`), pushes it to S3, updates `manifest.json`, and releases the lock.

---

### Implementation Instructions & Execution Order

Execute the implementation in strict order, writing complete, production-ready code with no placeholders or truncated sections:

#### Phase 1: Project Setup & Build Configuration
- Generate a standard Maven layout: `src/main/java/com/mcsync/...` and `src/main/resources/`.
- Provide a robust `pom.xml` targeting Java 21 with:
  - `software.amazon.awssdk:s3:2.25.+`
  - `com.github.luben:zstd-jni:1.5.6-0`
  - `org.apache.commons:commons-compress:1.26.1`
  - `com.fasterxml.jackson.core:jackson-databind:2.17.+`
  - `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.+`
  - `org.slf4j:slf4j-simple:2.0.+`
  - `maven-shade-plugin` configured to produce a runnable fat JAR (`mc-sync-launcher.jar`).

#### Phase 2: Configuration & Models
- `Config.java` record/POJO matching this JSON schema:
  - `playerId`, `localTailscaleIp`, `s3Endpoint`, `s3Region`, `s3Bucket`, `s3AccessKey`, `s3SecretKey`
  - `serverDirectory`, `serverStartCommand` (List<String>), `worldFolderName`
  - `clientLaunchCommand` (List<String>)
  - `discordWebhookUrl`, `heartbeatIntervalSeconds`, `lockTimeoutSeconds`, `maxBackupsToKeep`
- `LockFile.java`, `WorldManifest.java`, and `ModsManifest.java` domain models.

#### Phase 3: Core Managers
1. `LockManager.java`:
   - Distributed locking using S3 `PutObjectRequest` with conditional write header (`If-None-Match: *`).
   - Detection of `STALE` locks based on `Instant.now().getEpochSecond() - lock.lastHeartbeat > config.lockTimeoutSeconds` AND a failing TCP socket probe to `lock.hostIp:25565` (3s timeout).
   - A daemon thread (`ScheduledExecutorService`) updating `lastHeartbeat` every 30s.
   - Clean lock release via S3 `DeleteObjectRequest`.
2. `StorageSyncManager.java`:
   - World archiving/unarchiving using streaming `TarArchiveOutputStream` piped into `ZstdOutputStream` (and reverse for decompression).
   - SHA-256 computation to ensure archive integrity.
   - Rolling backup logic (`backups/world_<version>_<timestamp>.tar.zst`) retaining up to `maxBackupsToKeep`.
3. `ServerProcessController.java`:
   - Starts the server subprocess using `ProcessBuilder` in `serverDirectory`.
   - Modifies `server.properties` before boot to enforce `server-ip=<localTailscaleIp>`, `online-mode=true`, and `white-list=true`.
   - Asynchronously reads stdout/stderr to detect server readiness (`.*Done \\([0-9.,]+s\\)!.*`).
   - Tracks active player count via log parsing (`joined the game` / `left the game`) or periodically issuing `list\n` to `stdin`.
   - `stopGracefully()`: sends `save-all flush\n` followed by `stop\n`, then awaits process termination with a 60-second timeout.
4. `DiscordNotifier.java`:
   - Lightweight webhook dispatcher using standard Java 21 `HttpClient`.
5. `MainOrchestrator.java`:
   - Full orchestration lifecycle, state machine, and JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`).

#### Phase 4: Supporting Scripts
- `launch.bat` (Windows) and `launch.sh` (Linux/macOS) with error-trapping and environment checks (`JAVA_HOME`).
- An example `config.example.json`.

Begin by generating `pom.xml`, then proceed through the classes systematically.