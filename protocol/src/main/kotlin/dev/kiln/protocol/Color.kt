package dev.kiln.protocol

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/** A 32-bit ARGB color. */
@JvmInline
public value class Color(public val argb: Int) {
    public val alpha: Int get() = argb ushr 24
    public val red: Int get() = (argb shr 16) and 0xFF
    public val green: Int get() = (argb shr 8) and 0xFF
    public val blue: Int get() = argb and 0xFF

    override fun toString(): String = "#%08X".format(argb)

    /** Also the wire codec: the 4-byte ARGB value. */
    public companion object : Codec<Color> {
        public val WHITE: Color = Color(0xFFFFFFFF.toInt())
        public val BLACK: Color = Color(0xFF000000.toInt())
        public val TRANSPARENT: Color = Color(0)

        /** Opaque color from a 24-bit RGB value. */
        public fun rgb(rgb: Int): Color = Color(rgb or (0xFF shl 24))

        public fun argb(alpha: Int, red: Int, green: Int, blue: Int): Color =
            Color(((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF))

        override fun encode(writer: ProtocolWriter, value: Color): Unit = writer.int(value.argb)

        override fun decode(reader: ProtocolReader): Color = Color(reader.int())
    }
}
