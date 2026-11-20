package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_NUMERIC_VALUE

/** Range checks for floating-point fields; all of them reject NaN. */
internal object NumberChecks {
    fun checkFinite(value: Float, what: String) {
        if (!value.isFinite()) throw ProtocolException("$what is not finite")
    }

    fun checkWithin(value: Float, range: ClosedFloatingPointRange<Float>, what: String) {
        if (value !in range) throw ProtocolException("$what $value outside $range")
    }

    /** Finite and within ±[MAX_NUMERIC_VALUE], where doubles still represent every integer exactly. */
    fun checkNumber(value: Double, what: String) {
        if (!value.isFinite() || value < -MAX_NUMERIC_VALUE || value > MAX_NUMERIC_VALUE) {
            throw ProtocolException("$what out of range: $value")
        }
    }

    fun checkRange(min: Double, max: Double, what: String) {
        checkNumber(min, "$what min")
        checkNumber(max, "$what max")
        if (min > max) throw ProtocolException("$what: min $min > max $max")
    }
}
