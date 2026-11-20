package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_MOUSE_BUTTON
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.ScreenIdCodec

/**
 * Click on a [Button], [Custom], [ItemSlot] or clickable [Panel].
 * [button]: 0 = left, 1 = right, 2 = middle (GLFW mouse button numbers); keyboard activation counts as left.
 * [modifiers]: [ClickModifiers] held during the click.
 */
public data class ScreenClick(val screenId: Int, val elementId: String, val button: Int, val modifiers: Int = 0) :
    ServerboundMessage {
    val shift: Boolean get() = modifiers and ClickModifiers.SHIFT != 0

    /** Wire codec: screen id, element id, button (0..[MAX_MOUSE_BUTTON]), modifiers (within [ClickModifiers.ALL]). */
    public companion object : Codec<ScreenClick> {
        private val button = RangedVarIntCodec(0, MAX_MOUSE_BUTTON, "mouse button")
        private val modifiers = RangedVarIntCodec(0, ClickModifiers.ALL, "click modifiers")

        override fun encode(writer: ProtocolWriter, value: ScreenClick) {
            ScreenIdCodec.encode(writer, value.screenId)
            IdCodec.ELEMENT.encode(writer, value.elementId)
            button.encode(writer, value.button)
            modifiers.encode(writer, value.modifiers)
        }

        override fun decode(reader: ProtocolReader): ScreenClick = ScreenClick(
            screenId = ScreenIdCodec.decode(reader),
            elementId = IdCodec.ELEMENT.decode(reader),
            button = button.decode(reader),
            modifiers = modifiers.decode(reader),
        )
    }
}
