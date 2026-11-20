package dev.kiln.protocol.codec

/** An 8-byte IEEE double; restrict it with [validated]. */
public object DoubleCodec : Codec<Double> {
    override fun encode(writer: ProtocolWriter, value: Double): Unit = writer.double(value)

    override fun decode(reader: ProtocolReader): Double = reader.double()
}
