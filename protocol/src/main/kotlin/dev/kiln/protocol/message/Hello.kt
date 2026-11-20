package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSIONS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/**
 * First client message, sent in the **configuration phase** after announcing [CHANNEL] via `minecraft:register`.
 * Its layout never changes between protocol versions so that a mismatch can always be detected; everything else
 * the client reports goes into separate messages (e.g. [Viewport]).
 * [extensions]: legacy field, empty since v3 (the server ignores it).
 */
public data class Hello(val protocolVersion: Int, val extensions: List<String>) : ServerboundMessage {
    /** Wire codec; its layout must never change (see above). */
    public companion object : Codec<Hello> {
        private val extensions = IdCodec.LEGACY_EXTENSION.list(MAX_EXTENSIONS, "extensions")

        override fun encode(writer: ProtocolWriter, value: Hello) {
            VarIntCodec.encode(writer, value.protocolVersion)
            extensions.encode(writer, value.extensions)
        }

        override fun decode(reader: ProtocolReader): Hello =
            Hello(VarIntCodec.decode(reader), extensions.decode(reader))
    }
}
