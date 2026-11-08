package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Content of {@code lock.json} in the bucket. Its mere existence means "somebody is hosting".
 *
 * <pre>
 * {
 *   "ownerId": "player_alpha",
 *   "hostIp": "100.64.0.1",
 *   "lastHeartbeat": 1700000030,
 *   "sessionId": "uuid-v4"
 * }
 * </pre>
 *
 * Extra fields ({@code status}, {@code port}, {@code acquiredAt}) are extensions used to let
 * clients wait for a host that is still booting or already shutting down.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LockFile(
        String ownerId,
        String hostIp,
        long lastHeartbeat,
        String sessionId,
        Status status,
        Integer port,
        Long acquiredAt) {

    /** Host lifecycle phase published inside the lock. */
    public enum Status {
        /** Lock acquired, world/mod sync or server boot in progress. */
        STARTING,
        /** Server is up and accepting connections. */
        ONLINE,
        /** Host is stopping the server and uploading the world. */
        STOPPING
    }

    public LockFile {
        if (status == null) status = Status.ONLINE;
        if (port == null) port = 25565;
    }

    public LockFile withHeartbeat(long epochSeconds) {
        return new LockFile(ownerId, hostIp, epochSeconds, sessionId, status, port, acquiredAt);
    }

    public LockFile withStatus(Status newStatus) {
        return new LockFile(ownerId, hostIp, lastHeartbeat, sessionId, newStatus, port, acquiredAt);
    }

    public String address() {
        return port == 25565 ? hostIp : hostIp + ":" + port;
    }
}
