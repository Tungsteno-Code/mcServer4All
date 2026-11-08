package com.mcsync.world;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mcsync.storage.S3Storage;
import com.mcsync.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code registry.json} at the repo root: index of the worlds present in the repo. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorldRegistry(int schemaVersion, List<Entry> worlds) {

    public static final String KEY = "registry.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String id, String displayName, String createdBy, long createdAt) {
    }

    public WorldRegistry {
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
    }

    public static WorldRegistry empty() {
        return new WorldRegistry(1, List.of());
    }

    public boolean contains(String id) {
        return worlds.stream().anyMatch(w -> w.id().equals(id));
    }

    /** Reads the registry of a repo-root storage (empty if absent). */
    public static WorldRegistry read(S3Storage rootStorage) throws IOException {
        Optional<S3Storage.Fetched> f = rootStorage.get(KEY);
        return f.isEmpty() ? empty() : Json.fromBytes(f.get().data(), WorldRegistry.class);
    }

    /** Adds {@code entry} (replacing the same id) with an optimistic-concurrency retry loop. */
    public static void register(S3Storage rootStorage, Entry entry) throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<S3Storage.Fetched> f = rootStorage.get(KEY);
            WorldRegistry cur = f.isEmpty() ? empty() : Json.fromBytes(f.get().data(), WorldRegistry.class);
            List<Entry> list = new ArrayList<>(cur.worlds());
            list.removeIf(e -> e.id().equals(entry.id()));
            list.add(entry);
            byte[] data = Json.toBytes(new WorldRegistry(1, list));
            if (f.isEmpty()) {
                if (rootStorage.putIfAbsent(KEY, data, "application/json").isPresent()) return;
            } else if (rootStorage.putIfMatch(KEY, data, "application/json", f.get().eTag()).isPresent()) {
                return;
            }
        }
        throw new IOException("Impossibile aggiornare registry.json per modifiche concorrenti");
    }
}
