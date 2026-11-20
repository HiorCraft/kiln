package dev.kiln.server.runtime.screen

/** Outcome of checking a client screen event against the screen state the server last sent. */
sealed interface Validation {
    data object Valid : Validation

    data class Invalid(val reason: String) : Validation
}
