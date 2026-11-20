package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/** Play phase (v7): traits of entity [entityId] (sent when it is tracked): [solid] = collide like a boat. */
public data class EntityTraits(val entityId: Int, val solid: Boolean) : ClientboundMessage {
    /** Wire codec: entity id, [solid]. */
    public companion object : Codec<EntityTraits> {
        override fun encode(writer: ProtocolWriter, value: EntityTraits) {
            VarIntCodec.encode(writer, value.entityId)
            writer.boolean(value.solid)
        }

        override fun decode(reader: ProtocolReader): EntityTraits =
            EntityTraits(VarIntCodec.decode(reader), reader.boolean())
    }
}
