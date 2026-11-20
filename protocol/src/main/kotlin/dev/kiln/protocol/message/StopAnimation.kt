package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/** Play phase (v8): stops animation [name] (`null` = every server animation) of the entity. */
public data class StopAnimation(val entityId: Int, val name: String?) : ClientboundMessage {
    /** Wire codec: entity id, optional animation name. */
    public companion object : Codec<StopAnimation> {
        private val name = animationNameCodec.nullable()

        override fun encode(writer: ProtocolWriter, value: StopAnimation) {
            VarIntCodec.encode(writer, value.entityId)
            name.encode(writer, value.name)
        }

        override fun decode(reader: ProtocolReader): StopAnimation =
            StopAnimation(VarIntCodec.decode(reader), name.decode(reader))
    }
}
