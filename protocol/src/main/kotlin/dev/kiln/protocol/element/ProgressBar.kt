package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.FloatCodec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

public data class ProgressBar(
    override val id: String,
    override val bounds: Bounds,
    /** 0.0 .. 1.0 */
    val value: Float,
    val color: Color = Color.rgb(0x55FF55),
    val background: Color = Color.rgb(0x333333),
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<ProgressBar> {
        private val progress = FloatCodec.validated { NumberChecks.checkWithin(it, 0f..1f, "Progress value") }

        override fun encode(writer: ProtocolWriter, value: ProgressBar) {
            ElementHeader.encode(writer, ElementHeader.of(value))
            progress.encode(writer, value.value)
            Color.encode(writer, value.color)
            Color.encode(writer, value.background)
        }

        override fun decode(reader: ProtocolReader): ProgressBar {
            val header = ElementHeader.decode(reader)
            return ProgressBar(
                id = header.id,
                bounds = header.bounds,
                value = progress.decode(reader),
                color = Color.decode(reader),
                background = Color.decode(reader),
                tooltip = header.tooltip,
            )
        }
    }
}
