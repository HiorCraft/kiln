package dev.kiln.client.screen.widget

import dev.kiln.client.screen.TextFieldSync
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.element.CheckboxStyle
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util

/** Checkbox with the vanilla checkbox sprites, or a switch ([CheckboxStyle.TOGGLE]) built from slider sprites. */
class ServerCheckbox(private val env: WidgetEnv, private val elementId: String, initial: Checkbox) :
    AbstractWidget(0, 0, 0, 0, Component.empty()) {

    var checked: Boolean = initial.checked
        private set
    private var style = initial.style
    private var label: Component? = null
    private val sync = TextFieldSync(initial.checked.toString())

    /** Knob position of the toggle (0 = off, 1 = on), animated towards [checked]. */
    private var knob = if (initial.checked) 1f else 0f
    private var lastFrame = 0L

    fun apply(e: Checkbox, label: Component?) {
        style = e.style
        active = e.enabled
        this.label = label
        setMessage(label ?: Component.empty())
        if (sync.onServerValue(e.checked.toString(), checked.toString())) checked = e.checked
    }

    private fun toggle() {
        checked = !checked
        sync.sendNow(env, elementId, checked.toString())
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) = toggle()

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!active || !(event.isSelection || event.isConfirmation)) return false
        playDownSound(Minecraft.getInstance().soundManager)
        toggle()
        return true
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val box = height.coerceIn(9, 20)
        val boxY = y + (height - box) / 2
        val tint = if (active) -1 else WidgetColors.DISABLED_TINT
        val highlighted = active && isHoveredOrFocused
        val controlWidth = when (style) {
            CheckboxStyle.CHECKBOX -> {
                val sprite = when {
                    checked && highlighted -> CHECKBOX_SELECTED_HIGHLIGHTED
                    checked -> CHECKBOX_SELECTED
                    highlighted -> CHECKBOX_HIGHLIGHTED
                    else -> CHECKBOX
                }
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, boxY, box, box, tint)
                box
            }
            CheckboxStyle.TOGGLE -> extractToggle(graphics, boxY, box, highlighted, tint)
        }
        label?.let {
            val textY = y + (height - env.font.lineHeight) / 2 + 1
            graphics.text(env.font, it, x + controlWidth + 4, textY, if (active) WidgetColors.LABEL else WidgetColors.LABEL_DISABLED, true)
        }
        handleCursor(graphics)
    }

    private fun extractToggle(graphics: GuiGraphicsExtractor, boxY: Int, box: Int, highlighted: Boolean, tint: Int): Int {
        val now = Util.getMillis()
        val dt = if (lastFrame == 0L) 1f else ((now - lastFrame) / TOGGLE_ANIMATION_MS).coerceIn(0f, 1f)
        lastFrame = now
        val target = if (checked) 1f else 0f
        knob = if (knob < target) minOf(target, knob + dt) else maxOf(target, knob - dt)

        val trackWidth = box * 2
        val trackTop = boxY + 3
        val trackBottom = boxY + box - 3
        val fill = if (!active) 0xFF3A3A3A.toInt() else if (checked) 0xFF3C8527.toInt() else 0xFF4A4A4A.toInt()
        graphics.fill(x, trackTop, x + trackWidth, trackBottom, if (highlighted) -1 else 0xFF000000.toInt())
        graphics.fill(x + 1, trackTop + 1, x + trackWidth - 1, trackBottom - 1, fill)
        graphics.fill(x + 1, trackTop + 1, x + trackWidth - 1, trackTop + 2, 0x30FFFFFF)
        val knobX = x + ((trackWidth - KNOB_WIDTH) * knob).toInt()
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, if (highlighted) HANDLE_HIGHLIGHTED else HANDLE, knobX, boxY, KNOB_WIDTH, box, tint)
        return trackWidth
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private companion object {
        val CHECKBOX_SELECTED_HIGHLIGHTED: Identifier = Identifier.withDefaultNamespace("widget/checkbox_selected_highlighted")
        val CHECKBOX_SELECTED: Identifier = Identifier.withDefaultNamespace("widget/checkbox_selected")
        val CHECKBOX_HIGHLIGHTED: Identifier = Identifier.withDefaultNamespace("widget/checkbox_highlighted")
        val CHECKBOX: Identifier = Identifier.withDefaultNamespace("widget/checkbox")
        val HANDLE: Identifier = Identifier.withDefaultNamespace("widget/slider_handle")
        val HANDLE_HIGHLIGHTED: Identifier = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
        const val KNOB_WIDTH = 8
        const val TOGGLE_ANIMATION_MS = 120f
    }
}
