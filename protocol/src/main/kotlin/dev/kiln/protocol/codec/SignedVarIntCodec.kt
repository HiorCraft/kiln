package dev.kiln.protocol.codec

/** A zigzag varint (small negative values stay short) that must lie in [min]..[max]. */
public class SignedVarIntCodec(private val min: Int, private val max: Int, private val what: String) : Codec<Int> {
    override fun encode(writer: ProtocolWriter, value: Int) {
        checkIntInRange(value, min, max, what)
        writer.signedVarInt(value)
    }

    override fun decode(reader: ProtocolReader): Int =
        reader.signedVarInt().also { checkIntInRange(it, min, max, what) }
}
