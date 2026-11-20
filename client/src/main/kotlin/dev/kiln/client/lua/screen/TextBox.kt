package dev.kiln.client.lua.screen

import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.protocol.element.NumericRange

/** A native text field of a Lua screen (vanilla `EditBox` in the runtime, a fake in tests). */
interface TextBox {
    val value: String

    /** Replaces the content without reporting an edit. */
    fun setValue(value: String)

    var focused: Boolean

    fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean)

    /** Absolute GUI rectangle. */
    fun place(x: Int, y: Int, width: Int, height: Int)

    /** Draws the field now (inside the Lua render call, so it is layered and clipped like Lua drawing). */
    fun draw(target: DrawTarget, mouseX: Int, mouseY: Int)

    /** A click inside the field (cursor placement, selection). */
    fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean)
}
