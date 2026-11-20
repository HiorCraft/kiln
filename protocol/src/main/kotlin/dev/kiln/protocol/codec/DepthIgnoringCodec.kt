package dev.kiln.protocol.codec

/** A non-recursive [inner] codec where a [DepthCodec] is expected. */
public class DepthIgnoringCodec<T>(private val inner: Codec<T>) : DepthCodec<T> {
    override fun encode(writer: ProtocolWriter, value: T, depth: Int): Unit = inner.encode(writer, value)

    override fun decode(reader: ProtocolReader, depth: Int): T = inner.decode(reader)
}
