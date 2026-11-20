package dev.kiln.server.runtime.handshake

sealed interface HandshakeResult {
    data object Success : HandshakeResult
    data class Kick(val reason: String, val log: String) : HandshakeResult
    /** The client disconnected (e.g. declined the consent screen). */
    data object Closed : HandshakeResult
}
