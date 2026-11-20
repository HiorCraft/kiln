package dev.kiln.client.screen.widget

import dev.kiln.protocol.message.ClickModifiers
import net.minecraft.client.input.InputWithModifiers

/** [ClickModifiers] bits of an input event. */
object Modifiers {
    fun of(input: InputWithModifiers): Int =
        (if (input.hasShiftDown()) ClickModifiers.SHIFT else 0) or
            (if (input.hasControlDown()) ClickModifiers.CONTROL else 0) or
            (if (input.hasAltDown()) ClickModifiers.ALT else 0)
}
