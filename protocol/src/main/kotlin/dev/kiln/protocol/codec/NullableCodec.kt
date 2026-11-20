package dev.kiln.protocol.codec

/** [inner] behind a presence flag: `boolean`, then the value if it is not `null`. */
public class NullableCodec<T>(private val inner: Codec<T>) : Codec<T?> {
    override fun encode(writer: ProtocolWriter, value: T?) {
        writer.boolean(value != null)
        if (value != null) inner.encode(writer, value)
    }

    override fun decode(reader: ProtocolReader): T? = if (reader.boolean()) inner.decode(reader) else null
}
