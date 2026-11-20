package dev.kiln.client.screen

import dev.kiln.protocol.NumericRange
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong

/** Item count text for slots: plain up to 999, then abbreviated (`1.2k`, `12k`, `999k`, `1.5M`, `1B`), never rounded up. */
object CountFormat {
    fun format(count: Int): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> scaled(count, 1_000, "k")
        count < 1_000_000_000 -> scaled(count, 1_000_000, "M")
        else -> scaled(count, 1_000_000_000, "B")
    }

    private fun scaled(count: Int, unit: Int, suffix: String): String {
        val whole = count / unit
        if (whole >= 10) return "$whole$suffix"
        val tenths = (count / (unit / 10)) % 10
        return if (tenths == 0) "$whole$suffix" else "$whole.$tenths$suffix"
    }

    /** Text for the slot decoration: `null` (nothing drawn) for a count of 1. */
    fun decoration(count: Int): String? = if (count == 1) null else format(count)
}

/** Value math of a slider over `min..max` with an optional [step] (`0` = continuous). */
class SliderMath(val min: Double, val max: Double, val step: Double) {
    /** Decimal places used for snapping and formatting. */
    val decimals: Int = if (step > 0.0) {
        maxOf(scaleOf(step), scaleOf(min)).coerceIn(0, 10)
    } else {
        val range = max - min
        if (range <= 0.0 || !range.isFinite()) 2 else (2 - floor(log10(range)).toInt()).coerceIn(0, 6)
    }

    fun clamp(value: Double): Double = if (max <= min || !value.isFinite()) min else value.coerceIn(min, max)

    /** Nearest valid value: clamped and snapped to the step grid (or rounded to [decimals] when continuous). */
    fun snap(value: Double): Double {
        val clamped = clamp(value)
        var snapped = if (step > 0.0) min + ((clamped - min) / step).roundToLong() * step else clamped
        // max stays reachable even when it is not on the step grid
        if (step > 0.0 && kotlin.math.abs(max - clamped) < kotlin.math.abs(snapped - clamped)) snapped = max
        val rounded = BigDecimal(snapped).setScale(decimals, RoundingMode.HALF_UP).toDouble()
        return rounded.coerceIn(min, maxOf(min, max))
    }

    /** 0..1 position of [value] on the track. */
    fun toNormalized(value: Double): Double = if (max <= min) 0.0 else ((clamp(value) - min) / (max - min)).coerceIn(0.0, 1.0)

    fun fromNormalized(position: Double): Double = snap(min + position.coerceIn(0.0, 1.0) * (max - min))

    /** Keyboard increment: one step, or 1 % of the range for continuous sliders. */
    val keyboardIncrement: Double get() = if (step > 0.0) step else (max - min) / 100.0

    /** Plain decimal text (no exponent, no trailing zeros; whole numbers without fraction). */
    fun format(value: Double): String {
        if (!value.isFinite()) return "0"
        val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    private companion object {
        fun scaleOf(value: Double): Int =
            if (!value.isFinite()) 0 else BigDecimal(value.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
    }
}

/** Input rules of a numeric [dev.kiln.protocol.TextField]. */
object NumericInput {
    private val INTEGER = Regex("-?\\d*")
    private val DECIMAL = Regex("-?\\d*(\\.\\d*)?")

    /**
     * `true` if [text] may be the content of the field while typing: the empty string, a lone `-` (only if negative
     * numbers are allowed) or a prefix of a valid number. Values outside the range are allowed while typing but not
     * reported (see [reportable]).
     */
    fun isPartial(text: String, range: NumericRange): Boolean {
        if (text.isEmpty()) return true
        if (text.startsWith("-") && range.min >= 0.0) return false
        return (if (range.integer) INTEGER else DECIMAL).matches(text)
    }

    /** `true` if [text] may be sent to the server: empty or a number the range accepts. */
    fun reportable(text: String, range: NumericRange): Boolean = text.isEmpty() || range.accepts(text)
}
