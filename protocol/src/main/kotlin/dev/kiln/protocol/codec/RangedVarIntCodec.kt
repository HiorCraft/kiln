package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException

/** A varint that must lie in [min]..[max]; [what] names it in error messages. */
public class RangedVarIntCodec(private val min: Int, private val max: Int, private val what: String) : Codec<Int> {
    override fun encode(writer: ProtocolWriter, value: Int) {
        checkIntInRange(value, min, max, what)
        writer.varInt(value)
    }

    override fun decode(reader: ProtocolReader): Int = reader.varInt().also { checkIntInRange(it, min, max, what) }
}

internal fun checkIntInRange(value: Int, min: Int, max: Int, what: String) {
    if (value < min || value > max) throw ProtocolException("$what out of range: $value (allowed $min..$max)")
}
