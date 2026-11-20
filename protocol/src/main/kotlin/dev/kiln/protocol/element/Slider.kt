package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec
import kotlin.math.abs
import kotlin.math.round

/** How far (in steps) a [Slider] value may lie off the step grid and still count as on it. */
private const val STEP_TOLERANCE = 1e-6

/**
 * Number slider over [min]..[max] snapping to [step] (`0` = continuous). The client sends [ScreenInput] with the
 * value formatted as a plain decimal number (`Double.toString`-compatible, integers without fraction when
 * [step] is whole) while dragging (throttled) and on release. [label] (component JSON) is drawn as `label: value`.
 */
public data class Slider(
    override val id: String,
    override val bounds: Bounds,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
    val label: String? = null,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** `true` if [candidate] lies in [min]..[max] and on the [step] grid (with a small tolerance). */
    public fun accepts(candidate: Double): Boolean {
        if (!candidate.isFinite() || candidate < min || candidate > max) return false
        if (step <= 0.0) return true
        val steps = (candidate - min) / step
        return abs(steps - round(steps)) <= STEP_TOLERANCE || candidate == max
    }

    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Slider> {
        private val label = StringCodec(MAX_STRING_LENGTH, "label").nullable()

        override fun encode(writer: ProtocolWriter, value: Slider) {
            checkSlider(value)
            ElementHeader.encode(writer, ElementHeader.of(value))
            writer.double(value.min)
            writer.double(value.max)
            writer.double(value.step)
            writer.double(value.value)
            label.encode(writer, value.label)
            writer.boolean(value.enabled)
        }

        override fun decode(reader: ProtocolReader): Slider {
            val header = ElementHeader.decode(reader)
            return Slider(
                id = header.id,
                bounds = header.bounds,
                min = reader.double(),
                max = reader.double(),
                step = reader.double(),
                value = reader.double(),
                label = label.decode(reader),
                enabled = reader.boolean(),
                tooltip = header.tooltip,
            ).also(::checkSlider)
        }

        /** A valid range, a non-negative step and a value inside the range. */
        private fun checkSlider(slider: Slider) {
            NumberChecks.checkRange(slider.min, slider.max, "slider")
            NumberChecks.checkNumber(slider.step, "slider step")
            if (slider.step < 0.0) throw ProtocolException("Slider step is negative: ${slider.step}")
            NumberChecks.checkNumber(slider.value, "slider value")
            if (slider.value < slider.min || slider.value > slider.max) {
                throw ProtocolException("Slider value ${slider.value} outside ${slider.min}..${slider.max}")
            }
        }
    }
}
