package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.ClientboundMessage

/** The configuration-phase connection as the handshake sees it (Paper glue or a test fake). */
interface HandshakeLink {
    /** Sends immediately; may throw if the connection is gone. */
    fun send(message: ClientboundMessage)

    fun isOpen(): Boolean

    /** Whether the client announced [dev.kiln.protocol.CHANNEL] via `minecraft:register`. */
    fun channelRegistered(): Boolean
}
