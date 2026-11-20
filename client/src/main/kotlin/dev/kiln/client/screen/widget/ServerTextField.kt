package dev.kiln.client.screen.widget

import dev.kiln.client.screen.TextFieldSync
import dev.kiln.protocol.element.TextField
import net.minecraft.network.chat.Component

/**
 * [NumericEditBox] with [TextFieldSync]-based reporting: numeric fields report only values the range accepts (or "").
 */
class ServerTextField(private val env: WidgetEnv, private val elementId: String, initialValue: String) : NumericEditBox(env.font) {

    private val sync = TextFieldSync(initialValue)
    private var initialized = false

    fun apply(e: TextField, placeholder: Component?) {
        setMaxLength(e.maxLength)
        placeholder?.let(::setHint)
        setMessage(placeholder ?: Component.empty())
        numeric = e.numeric
        if (!initialized) {
            initialized = true
            setValueSilently(e.value)
        } else if (sync.onServerValue(e.value, value)) {
            setValueSilently(e.value)
        }
        updateColor()
    }

    override fun onEdit(value: String) {
        if (reportable(value)) sync.onLocalEdit(value)
    }

    fun flush(force: Boolean) = sync.flush(env, elementId, force)
}
