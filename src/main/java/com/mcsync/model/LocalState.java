package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-PC, <b>per-world</b> state persisted in {@code <serverDirectory>/.mcsync/state.json}.
 *
 * @param worldVersion        version of the world snapshot currently on disk (0 = never synced)
 * @param worldSha256         hash of the archive the local world came from / was pushed as
 * @param lastSyncEpoch       epoch seconds of the last successful pull or push
 * @param dirty               true while a locally hosted session has modified the world and it
 *                            has not been pushed yet (crash detection)
 * @param dirtySessionId      lock session that made the world dirty
 * @param serverModsFingerprint fingerprint of the server mod set at the last successful mod sync
 * @param clientModsFingerprint fingerprint of the client mod set at the last successful mod sync
 * @param quickSyncCache      scope name -&gt; fingerprint of the remote manifest (as seen by this PC)
 *                            at the last successful client sync; when unchanged the per-file
 *                            check is skipped (instant start)
 * @param installedServerRecipeHash hash of recipe + lock the local server was last built from
 * @param appliedServerLockHash     {@link ModsLock#contentHash()} materialised in the server folder
 * @param appliedClientLockHash     {@link ModsLock#contentHash()} materialised in the Prism instance
 * @param modpackVersion            modpack version applied on this PC (0 = none)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocalState(
        long worldVersion,
        String worldSha256,
        long lastSyncEpoch,
        boolean dirty,
        String dirtySessionId,
        String serverModsFingerprint,
        String clientModsFingerprint,
        java.util.Map<String, String> quickSyncCache,
        String installedServerRecipeHash,
        String appliedServerLockHash,
        String appliedClientLockHash,
        long modpackVersion) {

    public LocalState {
        quickSyncCache = quickSyncCache == null ? java.util.Map.of() : java.util.Map.copyOf(quickSyncCache);
    }

    /** Pre-2.0 canonical constructor (kept for existing callers and tests). */
    public LocalState(long worldVersion, String worldSha256, long lastSyncEpoch, boolean dirty, String dirtySessionId,
                      String serverModsFingerprint, String clientModsFingerprint,
                      java.util.Map<String, String> quickSyncCache) {
        this(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, serverModsFingerprint,
                clientModsFingerprint, quickSyncCache, null, null, null, 0);
    }

    public LocalState(long worldVersion, String worldSha256, long lastSyncEpoch, boolean dirty, String dirtySessionId,
                      String serverModsFingerprint, String clientModsFingerprint) {
        this(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, serverModsFingerprint,
                clientModsFingerprint, null);
    }

    public static LocalState empty() {
        return new LocalState(0, null, 0, false, null, null, null, null);
    }

    public LocalState withQuickSync(String scope, String remoteFingerprint) {
        java.util.Map<String, String> m = new java.util.HashMap<>(quickSyncCache);
        if (remoteFingerprint == null) m.remove(scope);
        else m.put(scope, remoteFingerprint);
        return new LocalState(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId,
                serverModsFingerprint, clientModsFingerprint, m, installedServerRecipeHash,
                appliedServerLockHash, appliedClientLockHash, modpackVersion);
    }

    public LocalState withWorld(long version, String sha256, long epoch) {
        return new LocalState(version, sha256, epoch, false, null, serverModsFingerprint, clientModsFingerprint,
                quickSyncCache, installedServerRecipeHash, appliedServerLockHash, appliedClientLockHash,
                modpackVersion);
    }

    public LocalState withDirty(boolean d, String sessionId) {
        return new LocalState(worldVersion, worldSha256, lastSyncEpoch, d, d ? sessionId : null,
                serverModsFingerprint, clientModsFingerprint, quickSyncCache, installedServerRecipeHash,
                appliedServerLockHash, appliedClientLockHash, modpackVersion);
    }

    public LocalState withModsFingerprint(String scope, String fingerprint) {
        return "client".equals(scope)
                ? new LocalState(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, serverModsFingerprint,
                fingerprint, quickSyncCache, installedServerRecipeHash, appliedServerLockHash,
                appliedClientLockHash, modpackVersion)
                : new LocalState(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, fingerprint,
                clientModsFingerprint, quickSyncCache, installedServerRecipeHash, appliedServerLockHash,
                appliedClientLockHash, modpackVersion);
    }

    public LocalState withInstalledRecipeHash(String hash) {
        return new LocalState(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, serverModsFingerprint,
                clientModsFingerprint, quickSyncCache, hash, appliedServerLockHash, appliedClientLockHash,
                modpackVersion);
    }

    /** Records which lock content is materialised for {@code scope} ({@code server} or {@code client}). */
    public LocalState withAppliedLock(String scope, String lockHash, long version) {
        boolean client = "client".equals(scope);
        return new LocalState(worldVersion, worldSha256, lastSyncEpoch, dirty, dirtySessionId, serverModsFingerprint,
                clientModsFingerprint, quickSyncCache, installedServerRecipeHash,
                client ? appliedServerLockHash : lockHash, client ? lockHash : appliedClientLockHash, version);
    }
}
