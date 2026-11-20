package dev.kiln.server.api.screen

public enum class CloseReason {
    /** Escape / closed by the player. */
    PLAYER,

    /** [ScreenHandle.close] was called. */
    SERVER,

    /** Another screen was opened for the player. */
    REPLACED,

    /** The player left. */
    DISCONNECT,
}
