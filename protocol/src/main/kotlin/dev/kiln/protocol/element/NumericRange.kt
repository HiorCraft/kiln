package dev.kiln.protocol.element

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/** Numeric constraint of a [TextField]: the client only accepts numbers in [min]..[max] (whole numbers if [integer]). */
public data class NumericRange(val min: Double, val max: Double, val integer: Boolean = true) {
    /** `true` if [value] is a valid number for this range (decimal notation, `.` as separator). */
    public fun accepts(value: String): Boolean {
        val number = if (integer) value.toLongOrNull()?.toDouble() else value.toDoubleOrNull()
        return number != null && number.isFinite() && number >= min && number <= max
    }

    /** Wire codec: finite bounds with min <= max, then [integer]. */
    public companion object : Codec<NumericRange> {
        override fun encode(writer: ProtocolWriter, value: NumericRange) {
            NumberChecks.checkRange(value.min, value.max, "numeric range")
            writer.double(value.min)
            writer.double(value.max)
            writer.boolean(value.integer)
        }

        override fun decode(reader: ProtocolReader): NumericRange {
            val min = reader.double()
            val max = reader.double()
            NumberChecks.checkRange(min, max, "numeric range")
            return NumericRange(min, max, reader.boolean())
        }
    }
}
