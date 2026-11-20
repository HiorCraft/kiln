package dev.kiln.protocol.codec

/** Any `Int` as unsigned LEB128 varint (negative values take 5 bytes). */
public object VarIntCodec : Codec<Int> {
    override fun encode(writer: ProtocolWriter, value: Int): Unit = writer.varInt(value)

    override fun decode(reader: ProtocolReader): Int = reader.varInt()
}
