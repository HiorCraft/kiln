package dev.kiln.protocol.codec

/** UTF-8 text of at most [maxLength] UTF-16 chars; [what] names it in error messages. */
public class StringCodec(private val maxLength: Int, private val what: String) : Codec<String> {
    override fun encode(writer: ProtocolWriter, value: String): Unit = writer.string(value, maxLength, what)

    override fun decode(reader: ProtocolReader): String = reader.string(maxLength, what)
}
