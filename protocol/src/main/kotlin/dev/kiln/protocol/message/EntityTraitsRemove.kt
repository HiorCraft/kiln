package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_ENTITY_IDS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/** Play phase (v7): the entities stopped being tracked; their traits are dropped. */
public data class EntityTraitsRemove(val entityIds: List<Int>) : ClientboundMessage {
    /** Wire codec: the entity ids. */
    public companion object : Codec<EntityTraitsRemove> {
        private val entityIds = VarIntCodec.list(MAX_ENTITY_IDS, "entity ids")

        override fun encode(writer: ProtocolWriter, value: EntityTraitsRemove): Unit =
            entityIds.encode(writer, value.entityIds)

        override fun decode(reader: ProtocolReader): EntityTraitsRemove = EntityTraitsRemove(entityIds.decode(reader))
    }
}
