package dev.kiln.protocol.message

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.FloatCodec
import dev.kiln.protocol.codec.NamespacedIdCodec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.VarIntCodec

/**
 * Play phase (v8): entity [entityId] is drawn with the Kiln bone model [model] (`namespace:name`, files
 * `assets/<ns>/kiln/entity/<name>.geo.json` / `.animation.json`, texture `textures/entity/<name>.png`) and has a
 * [width] × [height] hit box; [headBone] = the bone `head` follows the look direction. Sent when it is tracked.
 */
public data class EntityAppearance(val entityId: Int, val model: String, val width: Float, val height: Float, val headBone: Boolean) :
    ClientboundMessage {
    /** Wire codec: entity id, model, hit box (0.05..16 blocks per dimension), [headBone]. */
    public companion object : Codec<EntityAppearance> {
        private val SIZE = 0.05f..16f

        private val model = NamespacedIdCodec("entity model")
        private val size = FloatCodec.validated { NumberChecks.checkWithin(it, SIZE, "Entity size") }

        override fun encode(writer: ProtocolWriter, value: EntityAppearance) {
            VarIntCodec.encode(writer, value.entityId)
            model.encode(writer, value.model)
            size.encode(writer, value.width)
            size.encode(writer, value.height)
            writer.boolean(value.headBone)
        }

        override fun decode(reader: ProtocolReader): EntityAppearance = EntityAppearance(
            entityId = VarIntCodec.decode(reader),
            model = model.decode(reader),
            width = size.decode(reader),
            height = size.decode(reader),
            headBone = reader.boolean(),
        )
    }
}
