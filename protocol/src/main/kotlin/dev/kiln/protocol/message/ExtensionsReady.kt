package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_ERROR_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/**
 * Configuration phase, last client message: every manifest package is verified, consented to and loaded ([ok]),
 * or not ([ok] = false, [error] = human-readable reason, shown in the disconnect screen). The server lets the
 * player enter the world only after `ok = true`; otherwise it disconnects the player.
 */
public data class ExtensionsReady(val ok: Boolean, val error: String? = null) : ServerboundMessage {
    /** Wire codec: [ok], then the optional [error]. */
    public companion object : Codec<ExtensionsReady> {
        private val error = StringCodec(MAX_EXTENSION_ERROR_LENGTH, "extension error").nullable()

        override fun encode(writer: ProtocolWriter, value: ExtensionsReady) {
            writer.boolean(value.ok)
            error.encode(writer, value.error)
        }

        override fun decode(reader: ProtocolReader): ExtensionsReady =
            ExtensionsReady(reader.boolean(), error.decode(reader))
    }
}
