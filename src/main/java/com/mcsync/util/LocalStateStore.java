package com.mcsync.util;

import com.mcsync.model.LocalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** Thread-safe persistence of {@link LocalState} in {@code .mcsync/state.json}. */
public final class LocalStateStore {

    private static final Logger log = LoggerFactory.getLogger(LocalStateStore.class);

    private final Path file;
    private LocalState state;

    public LocalStateStore(Path stateDir) {
        this.file = stateDir.resolve("state.json");
        LocalState loaded;
        try {
            loaded = Json.readFile(file, LocalState.class);
        } catch (IOException e) {
            log.warn("Local state file {} is unreadable ({}); starting from scratch", file, e.getMessage());
            loaded = null;
        }
        this.state = loaded != null ? loaded : LocalState.empty();
    }

    public synchronized LocalState get() {
        return state;
    }

    public synchronized LocalState update(UnaryOperator<LocalState> fn) {
        LocalState next = fn.apply(state);
        try {
            Json.writeFileAtomic(file, next);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
        state = next;
        return next;
    }
}
