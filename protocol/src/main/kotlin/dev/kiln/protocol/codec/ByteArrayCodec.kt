package dev.kiln.protocol.codec

/** Raw bytes, at most [maxLength] of them; [what] names them in error messages. */
public class ByteArrayCodec(private val maxLength: Int, private val what: String) : Codec<ByteArray> {
    override fun encode(writer: ProtocolWriter, value: ByteArray): Unit = writer.byteArray(value, maxLength, what)

    override fun decode(reader: ProtocolReader): ByteArray = reader.byteArray(maxLength, what)
}
