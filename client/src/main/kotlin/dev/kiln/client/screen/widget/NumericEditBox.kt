package dev.kiln.client.screen.widget

import dev.kiln.client.screen.NumericInput
import dev.kiln.protocol.element.NumericRange
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * Vanilla [EditBox] with optional numeric input: with a [numeric] range it rejects keystrokes/pastes that cannot
 * become a number ([NumericInput.isPartial]) and shows content the range does not accept in red. Edits by the player
 * go to [onEdit]; values set with [setValueSilently] do not.
 */
abstract class NumericEditBox(font: Font) : EditBox(font, 0, 0, 0, 0, Component.empty()) {
    var numeric: NumericRange? = null

    /** Suppresses the responder while the value is changed programmatically or validated. */
    private var silent = false

    init {
        setResponder { value -> if (!silent) edited(value) }
    }

    /** The player changed the value to [value] (already filtered, possibly not [reportable]). */
    protected abstract fun onEdit(value: String)

    /** `true` if [value] may be reported: always for text, only values the range accepts for numeric input. */
    fun reportable(value: String): Boolean = numeric.let { range -> range == null || NumericInput.reportable(value, range) }

    fun setValueSilently(newValue: String) {
        silently { value = newValue }
    }

    fun updateColor() {
        setTextColor(if (reportable(value)) EditBox.DEFAULT_TEXT_COLOR else WidgetColors.INVALID)
    }

    override fun insertText(input: String) {
        val range = numeric ?: return super.insertText(input)
        val oldValue = value
        val oldCursor = cursorPosition
        silently {
            super.insertText(input)
            if (!NumericInput.isPartial(value, range)) {
                value = oldValue
                setCursorPosition(oldCursor)
                setHighlightPos(oldCursor)
            }
        }
        if (value != oldValue) edited(value)
    }

    private fun edited(value: String) {
        updateColor()
        onEdit(value)
    }

    private inline fun silently(action: () -> Unit) {
        silent = true
        try {
            action()
        } finally {
            silent = false
        }
    }
}
