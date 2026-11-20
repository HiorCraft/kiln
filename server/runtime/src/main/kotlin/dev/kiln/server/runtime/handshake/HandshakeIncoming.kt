package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.Message
import dev.kiln.protocol.message.ServerboundMessage

/** What a [HandshakeInbox] holds: a decoded message, a decoding error, or the marker that the client flooded it. */
sealed interface HandshakeIncoming {
    data class Message(val message: ServerboundMessage) : HandshakeIncoming
    data class Malformed(val error: String) : HandshakeIncoming
    data object Overflow : HandshakeIncoming
}
