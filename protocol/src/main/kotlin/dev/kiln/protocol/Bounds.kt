package dev.kiln.protocol

import dev.kiln.protocol.ProtocolLimits.MAX_COORDINATE
import dev.kiln.protocol.ProtocolLimits.MAX_SIZE
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.SignedVarIntCodec

/** Position relative to the parent element and size, in GUI pixels. */
public data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    /** Wire codec: x/y as zigzag varints within ±[MAX_COORDINATE], width/height as varints up to [MAX_SIZE]. */
    public companion object : Codec<Bounds> {
        private val x = SignedVarIntCodec(-MAX_COORDINATE, MAX_COORDINATE, "x")
        private val y = SignedVarIntCodec(-MAX_COORDINATE, MAX_COORDINATE, "y")
        private val width = RangedVarIntCodec(0, MAX_SIZE, "width")
        private val height = RangedVarIntCodec(0, MAX_SIZE, "height")

        override fun encode(writer: ProtocolWriter, value: Bounds) {
            x.encode(writer, value.x)
            y.encode(writer, value.y)
            width.encode(writer, value.width)
            height.encode(writer, value.height)
        }

        override fun decode(reader: ProtocolReader): Bounds =
            Bounds(x.decode(reader), y.decode(reader), width.decode(reader), height.decode(reader))
    }
}
