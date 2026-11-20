package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolLimits.MAX_TEXTURE_SIZE
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.FloatCodec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec

/**
 * Draws the region ([u], [v], [regionWidth] x [regionHeight]) of a [textureWidth] x [textureHeight] texture,
 * scaled to [bounds]. Textures come from client extensions or vanilla.
 */
public data class Image(
    override val id: String,
    override val bounds: Bounds,
    val texture: ResourceId,
    val u: Float,
    val v: Float,
    val regionWidth: Int,
    val regionHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Image> {
        private val texture = ResourceId.codec("image texture")
        private val u = FloatCodec.validated { NumberChecks.checkFinite(it, "u") }
        private val v = FloatCodec.validated { NumberChecks.checkFinite(it, "v") }
        private val regionWidth = RangedVarIntCodec(0, MAX_TEXTURE_SIZE, "region width")
        private val regionHeight = RangedVarIntCodec(0, MAX_TEXTURE_SIZE, "region height")
        private val textureWidth = RangedVarIntCodec(1, MAX_TEXTURE_SIZE, "texture width")
        private val textureHeight = RangedVarIntCodec(1, MAX_TEXTURE_SIZE, "texture height")

        override fun encode(writer: ProtocolWriter, value: Image) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            texture.encode(writer, value.texture)
            u.encode(writer, value.u)
            v.encode(writer, value.v)
            regionWidth.encode(writer, value.regionWidth)
            regionHeight.encode(writer, value.regionHeight)
            textureWidth.encode(writer, value.textureWidth)
            textureHeight.encode(writer, value.textureHeight)
        }

        override fun decode(reader: ProtocolReader): Image {
            val header = ElementHeader.decode(reader)
            return Image(
                id = header.id,
                bounds = header.bounds,
                texture = texture.decode(reader),
                u = u.decode(reader),
                v = v.decode(reader),
                regionWidth = regionWidth.decode(reader),
                regionHeight = regionHeight.decode(reader),
                textureWidth = textureWidth.decode(reader),
                textureHeight = textureHeight.decode(reader),
                tooltip = header.tooltip,
            )
        }
    }
}
