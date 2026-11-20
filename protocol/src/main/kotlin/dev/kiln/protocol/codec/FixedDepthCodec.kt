package dev.kiln.protocol.codec

/** [inner] for values at a known [depth]. */
public class FixedDepthCodec<T>(private val inner: DepthCodec<T>, private val depth: Int) : Codec<T> {
    override fun encode(writer: ProtocolWriter, value: T): Unit = inner.encode(writer, value, depth)

    override fun decode(reader: ProtocolReader): T = inner.decode(reader, depth)
}
