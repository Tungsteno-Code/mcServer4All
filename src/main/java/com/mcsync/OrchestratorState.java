package com.mcsync;

/** Lifecycle states of {@link MainOrchestrator}. */
public enum OrchestratorState {
    INITIALIZING,
    CHECKING_LOCK,
    WAITING_FOR_HOST,
    CLIENT_MODE,
    ACQUIRING_LOCK,
    SYNCING_DOWN,
    SYNCING_MODS,
    STARTING_SERVER,
    HOSTING,
    DAEMON_HOSTING,
    SHUTTING_DOWN,
    SYNCING_UP,
    RELEASING_LOCK,
    TERMINATED,
    FAILED
}
