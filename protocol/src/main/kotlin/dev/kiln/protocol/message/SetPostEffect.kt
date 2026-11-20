package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.NamespacedIdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Play phase (v6): switches the client's post-processing effect to [id] (`<namespace>:<path>`, a
 * `post_effect/<path>.json` of a loaded extension's resources), or turns it off ([id] = `null`).
 * The client ignores unknown effects.
 */
public data class SetPostEffect(val id: String?) : ClientboundMessage {
    /** Wire codec: the optional `namespace:path` effect id. */
    public companion object : Codec<SetPostEffect> {
        private val effect = NamespacedIdCodec("post effect").nullable()

        override fun encode(writer: ProtocolWriter, value: SetPostEffect): Unit = effect.encode(writer, value.id)

        override fun decode(reader: ProtocolReader): SetPostEffect = SetPostEffect(effect.decode(reader))
    }
}
