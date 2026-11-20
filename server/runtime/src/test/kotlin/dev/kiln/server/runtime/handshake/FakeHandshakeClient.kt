package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.message.ServerboundMessage
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.server.runtime.extension.ExtensionPackage

/** Scripted client: reacts to server messages by putting its answers into the inbox. */
internal class FakeHandshakeClient(
    val inbox: HandshakeInbox = HandshakeInbox(),
    var registered: Boolean = true,
    val onManifest: (ExtensionManifest) -> ServerboundMessage? = { ExtensionRequest(it.extensions.map { e -> e.id }) },
    val ready: ServerboundMessage? = ExtensionsReady(true),
) : HandshakeLink {
    val sent = ArrayList<ClientboundMessage>()
    var open = true
    private var expectedChunks = 0

    override fun send(message: ClientboundMessage) {
        // everything must survive the real codec
        sent += MessageCodec.decodeClientbound(MessageCodec.encodeClientbound(message))
        when (message) {
            is ExtensionManifest -> {
                val answer = onManifest(message)
                val requested = (answer as? ExtensionRequest)?.ids?.toSet().orEmpty()
                expectedChunks = message.extensions.filter { it.id in requested }.sumOf { it.chunkCount }
                answer?.let(::reply)
                if (answer != null && expectedChunks == 0) ready?.let(::reply)
            }
            is ExtensionChunk -> if (--expectedChunks == 0) ready?.let(::reply)
            else -> Unit
        }
    }

    fun reply(message: ServerboundMessage) = inbox.offerBytes(MessageCodec.encodeServerbound(message))

    override fun isOpen(): Boolean = open

    override fun channelRegistered(): Boolean = registered

    fun run(packages: List<ExtensionPackage>, timeouts: HandshakeTimeouts) =
        ConfigurationHandshake(this, inbox, packages, timeouts).run()
}
