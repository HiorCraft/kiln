package dev.kiln.client.screen.widget

import dev.kiln.client.screen.SliderMath
import dev.kiln.client.screen.TextFieldSync
import dev.kiln.protocol.element.Slider
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Vanilla slider snapping to the element's step. While dragging, values are reported throttled ([flush] from the
 * screen tick), on release immediately. Arrow keys move by one step.
 */
class ServerSlider(private val env: WidgetEnv, private val elementId: String, initial: Slider) :
    AbstractSliderButton(0, 0, 0, 0, Component.empty(), 0.0) {

    private var math = SliderMath(initial.min, initial.max, initial.step)
    private var actual = math.snap(initial.value)
    private var label: Component? = null
    private val sync = TextFieldSync(math.format(actual))

    fun apply(e: Slider, label: Component?) {
        math = SliderMath(e.min, e.max, e.step)
        active = e.enabled
        this.label = label
        val incoming = math.snap(e.value)
        actual = if (sync.onServerValue(math.format(incoming), math.format(actual))) incoming else math.snap(actual)
        value = math.toNormalized(actual)
        updateMessage()
    }

    override fun setValue(newValue: Double) {
        val old = actual
        actual = math.fromNormalized(newValue)
        value = math.toNormalized(actual)
        if (actual != old) applyValue()
        updateMessage()
    }

    override fun applyValue() {
        sync.onLocalEdit(math.format(actual))
    }

    override fun updateMessage() {
        val number = Component.literal(math.format(actual))
        setMessage(label?.let { Component.empty().append(it).append(": ").append(number) } ?: number)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (canChangeValue && (event.isLeft || event.isRight)) {
            val direction = if (event.isLeft) -1 else 1
            setValue(math.toNormalized(actual + direction * math.keyboardIncrement))
            return true
        }
        return super.keyPressed(event)
    }

    override fun onRelease(event: MouseButtonEvent) {
        super.onRelease(event)
        flush(force = true)
    }

    fun flush(force: Boolean) = sync.flush(env, elementId, force)
}
