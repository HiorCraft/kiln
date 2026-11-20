package dev.kiln.protocol.codec

/** [inner] with [check] applied before encoding and after decoding; [check] throws for invalid values. */
public class ValidatedCodec<T>(private val inner: Codec<T>, private val check: (T) -> Unit) : Codec<T> {
    override fun encode(writer: ProtocolWriter, value: T) {
        check(value)
        inner.encode(writer, value)
    }

    override fun decode(reader: ProtocolReader): T = inner.decode(reader).also(check)
}
