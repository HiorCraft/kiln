package dev.kiln.client.ext

import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.screen.TextBox
import dev.kiln.client.screen.widget.NumericEditBox
import dev.kiln.protocol.element.NumericRange
import net.minecraft.client.gui.Font
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component

/**
 * [NumericEditBox] as a Lua text input of a [LuaScreen]: numeric filtering like server text fields, red text when
 * invalid. [currentEvent] is the screen's mouse event in progress (the edit box needs it for cursor placement).
 */
class EditBoxTextBox(
    font: Font,
    private val currentEvent: () -> MouseButtonEvent?,
    private val onEdit: (String) -> Unit,
) : TextBox {
    val edit: NumericEditBox = object : NumericEditBox(font) {
        override fun onEdit(value: String) = this@EditBoxTextBox.onEdit(value)
    }

    override val value: String get() = edit.value

    override fun setValue(value: String) {
        edit.setValueSilently(value)
        edit.updateColor()
    }

    override var focused: Boolean
        get() = edit.isFocused
        set(v) {
            edit.isFocused = v
        }

    override fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean) {
        edit.numeric = numeric
        edit.setMaxLength(maxLength)
        edit.setHint(Component.literal(placeholder))
        edit.setEditable(enabled)
        edit.active = enabled
        edit.updateColor()
    }

    override fun place(x: Int, y: Int, width: Int, height: Int) {
        edit.setRectangle(width, height, x, y)
    }

    override fun draw(target: DrawTarget, mouseX: Int, mouseY: Int) {
        val graphics = (target as? GuiDrawTarget)?.graphics ?: return
        edit.extractRenderState(graphics, mouseX, mouseY, 0f)
    }

    override fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean) {
        val event = currentEvent() ?: MouseButtonEvent(x, y, MouseButtonInfo(1, 0))
        edit.mouseClicked(event, doubleClick)
    }
}
