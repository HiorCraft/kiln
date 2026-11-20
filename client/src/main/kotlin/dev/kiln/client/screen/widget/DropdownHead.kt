package dev.kiln.client.screen.widget

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.screen.TextFieldSync
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.DropdownOption
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

private val BUTTON_SPRITES = WidgetSprites(
    Identifier.withDefaultNamespace("widget/button"),
    Identifier.withDefaultNamespace("widget/button_disabled"),
    Identifier.withDefaultNamespace("widget/button_highlighted"),
)

/**
 * Head of a [Dropdown]: a vanilla button showing the selected option (or the grey placeholder) and an arrow.
 * Clicking / Enter / Space / arrow keys open the option list, which the screen draws as an overlay.
 */
class DropdownHead(private val env: WidgetEnv, val elementId: String, initial: Dropdown) :
    AbstractWidget(0, 0, 0, 0, Component.empty()) {

    var options: List<DropdownOption> = initial.options
        private set
    var labels: List<Component> = emptyList()
        private set
    private var placeholder: Component? = null
    var selected: String? = initial.selected
        private set
    private val sync = TextFieldSync(encode(initial.selected))

    val selectedIndex: Int get() = options.indexOfFirst { it.value == selected }

    fun apply(e: Dropdown, labels: List<Component>, placeholder: Component?) {
        options = e.options
        this.labels = labels
        this.placeholder = placeholder
        active = e.enabled
        if (sync.onServerValue(encode(e.selected), encode(selected))) selected = e.selected
        if (selected != null && selectedIndex < 0) selected = e.selected
        setMessage(labels.getOrNull(selectedIndex) ?: placeholder ?: Component.empty())
    }

    /** The player chose option [index] in the overlay. */
    fun choose(index: Int) {
        val option = options.getOrNull(index) ?: return
        selected = option.value
        setMessage(labels.getOrNull(index) ?: Component.literal(option.value))
        sync.sendNow(env, elementId, option.value)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) = env.toggleDropdown(this)

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!active || !(event.isSelection || event.isConfirmation || event.isDown || event.isUp)) return false
        env.toggleDropdown(this)
        return true
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val open = env.isDropdownOpen(this)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON_SPRITES.get(active, isHoveredOrFocused || open), x, y, width, height)
        val index = selectedIndex
        val text = labels.getOrNull(index) ?: placeholder
        val color = if (active && index >= 0) -1 else WidgetColors.LABEL_DISABLED
        if (text != null) {
            graphics.enableScissor(x + 4, y, x + width - ARROW_AREA, y + height)
            graphics.text(env.font, text, x + 5, y + (height - env.font.lineHeight) / 2 + 1, color, true)
            graphics.disableScissor()
        }
        extractArrow(graphics, x + width - ARROW_AREA / 2 - 4, y + height / 2 - 2, open, if (active) -1 else WidgetColors.LABEL_DISABLED)
        handleCursor(graphics)
    }

    override fun handleCursor(graphics: GuiGraphicsExtractor) {
        if (isHovered) graphics.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private companion object {
        const val ARROW_AREA = 14
        const val NONE = "\u0000"

        fun encode(value: String?): String = value ?: NONE

        /** Small triangle (7 px wide, 4 rows) pointing down, or up when [up]. */
        fun extractArrow(graphics: GuiGraphicsExtractor, left: Int, top: Int, up: Boolean, color: Int) {
            for (row in 0 until 4) {
                val inset = if (up) 3 - row else row
                graphics.fill(left + inset, top + row, left + 7 - inset, top + row + 1, color)
            }
        }
    }
}
