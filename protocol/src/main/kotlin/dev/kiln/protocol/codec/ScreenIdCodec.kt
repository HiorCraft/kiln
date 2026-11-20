package dev.kiln.protocol.codec

/** A screen id: any non-negative `Int`. */
public object ScreenIdCodec : Codec<Int> {
    private val id = RangedVarIntCodec(0, Int.MAX_VALUE, "screen id")

    override fun encode(writer: ProtocolWriter, value: Int): Unit = id.encode(writer, value)

    override fun decode(reader: ProtocolReader): Int = id.decode(reader)
}
