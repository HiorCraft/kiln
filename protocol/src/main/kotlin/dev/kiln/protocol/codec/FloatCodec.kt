package dev.kiln.protocol.codec

/** A 4-byte IEEE float; restrict it with [validated]. */
public object FloatCodec : Codec<Float> {
    override fun encode(writer: ProtocolWriter, value: Float): Unit = writer.float(value)

    override fun decode(reader: ProtocolReader): Float = reader.float()
}
