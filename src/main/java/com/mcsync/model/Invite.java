package com.mcsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Content of a {@code .mcsync} invite file. <b>It contains the repository token in clear text</b>:
 * treat the file like a key (see PIANIFICAZIONE-DEFINITIVA §8).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Invite(int schemaVersion, String worldId, String displayName, String minecraft,
                     ModsLock.Loader loader, Storage storage, long modpackVersion, String lockSha256,
                     String createdBy, String minLauncherVersion, String curseforgeApiKey) {

    public static final int SCHEMA_VERSION = 1;

    public Invite(int schemaVersion, String worldId, String displayName, String minecraft,
                  ModsLock.Loader loader, Storage storage, long modpackVersion, String lockSha256,
                  String createdBy, String minLauncherVersion) {
        this(schemaVersion, worldId, displayName, minecraft, loader, storage, modpackVersion,
                lockSha256, createdBy, minLauncherVersion, null);
    }

    /**
     * @param backend   {@code GIT} or {@code S3}
     * @param path      {@code worlds/<id>} (informative; the importer derives it from the world id)
     * @param prefix    repo-wide key prefix configured by the creator (usually blank)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Storage(String backend, String remoteUrl, String branch, String path, String token, String prefix,
                          String s3Endpoint, String s3Region, String s3Bucket, String s3AccessKey,
                          String s3SecretKey, Boolean s3PathStyle) {
    }
}
