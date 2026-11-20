package dev.kiln.client.screen

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.protocol.Button
import dev.kiln.protocol.Checkbox
import dev.kiln.protocol.CheckboxStyle
import dev.kiln.protocol.ClickModifiers
import dev.kiln.protocol.Dropdown
import dev.kiln.protocol.DropdownOption
import dev.kiln.protocol.NumericRange
import dev.kiln.protocol.ScreenClick
import dev.kiln.protocol.ScreenInput
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.Slider
import dev.kiln.protocol.TextField
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util

/** What the widgets of a [KilnScreen] need from it. */
interface WidgetEnv {
    val screenId: Int
    val font: Font
    fun send(message: ServerboundMessage): Boolean
    fun toggleDropdown(head: DropdownHead)
    fun isDropdownOpen(head: DropdownHead): Boolean
}

/** [ClickModifiers] bits of an input event. */
object Modifiers {
    fun of(input: InputWithModifiers): Int =
        (if (input.hasShiftDown()) ClickModifiers.SHIFT else 0) or
            (if (input.hasControlDown()) ClickModifiers.CONTROL else 0) or
            (if (input.hasAltDown()) ClickModifiers.ALT else 0)
}

/** Shared colors of the runtime's own widgets (vanilla values). */
internal object WidgetColors {
    const val LABEL = 0xFFE0E0E0.toInt()
    const val LABEL_DISABLED = 0xFFA0A0A0.toInt()
    const val INVALID = 0xFFFF5555.toInt()
    const val DISABLED_TINT = 0xFF909090.toInt()
}

private val BUTTON_SPRITES = WidgetSprites(
    Identifier.withDefaultNamespace("widget/button"),
    Identifier.withDefaultNamespace("widget/button_disabled"),
    Identifier.withDefaultNamespace("widget/button_highlighted"),
)

/** Sends a value change of an input element: reports [value] immediately (no throttling). */
private fun TextFieldSync.sendNow(env: WidgetEnv, elementId: String, value: String) {
    onLocalEdit(value)
    poll(Util.getMillis(), force = true)?.let { env.send(ScreenInput(env.screenId, elementId, it)) }
}

/** Vanilla-looking button that reports every mouse button (and keyboard activation as left click) with modifiers. */
class ServerButton(private val env: WidgetEnv, private val elementId: String) : AbstractButton(0, 0, 0, 0, Component.empty()) {

    fun apply(e: Button, label: Component?) {
        // setMessage, not the field: WithInactiveMessage derives the greyed label of a disabled button there
        setMessage(label ?: Component.empty())
        active = e.enabled
    }

    override fun onPress(input: InputWithModifiers) {
        val button = if (input is MouseButtonEvent) MouseButtons.toProtocol(input.button()) else 0
        if (button >= 0) env.send(ScreenClick(env.screenId, elementId, button, Modifiers.of(input)))
    }

    override fun isValidClickButton(buttonInfo: MouseButtonInfo): Boolean = MouseButtons.toProtocol(buttonInfo.button()) >= 0

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(graphics)
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE))
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}

/**
 * Vanilla [EditBox] with [TextFieldSync]-based reporting. Numeric fields reject keystrokes/pastes that cannot
 * become a number ([NumericInput.isPartial]), show out-of-range content in red and report only values the range
 * accepts (or "").
 */
class ServerTextField(private val env: WidgetEnv, private val elementId: String, initialValue: String) :
    EditBox(env.font, 0, 0, 0, 0, Component.empty()) {

    private val sync = TextFieldSync(initialValue)
    private var applyingServerValue = false
    private var initialized = false
    private var numeric: NumericRange? = null

    init {
        setResponder { value -> if (!applyingServerValue) onLocalEdit(value) }
    }

    fun apply(e: TextField, placeholder: Component?) {
        setMaxLength(e.maxLength)
        placeholder?.let(::setHint)
        setMessage(placeholder ?: Component.empty())
        numeric = e.numeric
        if (!initialized) {
            initialized = true
            setServerValue(e.value)
        } else if (sync.onServerValue(e.value, value)) {
            setServerValue(e.value)
        }
        updateColor()
    }

    private fun onLocalEdit(value: String) {
        updateColor()
        val range = numeric
        if (range == null || NumericInput.reportable(value, range)) sync.onLocalEdit(value)
    }

    private fun updateColor() {
        val range = numeric
        setTextColor(if (range != null && !NumericInput.reportable(value, range)) WidgetColors.INVALID else EditBox.DEFAULT_TEXT_COLOR)
    }

    override fun insertText(input: String) {
        val range = numeric ?: return super.insertText(input)
        val oldValue = value
        val oldCursor = cursorPosition
        applyingServerValue = true // suppress the responder until the result is validated
        try {
            super.insertText(input)
            if (!NumericInput.isPartial(value, range)) {
                value = oldValue
                setCursorPosition(oldCursor)
                setHighlightPos(oldCursor)
            }
        } finally {
            applyingServerValue = false
        }
        if (value != oldValue) onLocalEdit(value)
    }

    private fun setServerValue(newValue: String) {
        applyingServerValue = true
        try {
            value = newValue
        } finally {
            applyingServerValue = false
        }
    }

    fun flush(force: Boolean) {
        sync.poll(Util.getMillis(), force)?.let { env.send(ScreenInput(env.screenId, elementId, it)) }
    }
}

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
        playDownSound(net.minecraft.client.Minecraft.getInstance().soundManager)
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

    fun flush(force: Boolean) {
        sync.poll(Util.getMillis(), force)?.let { env.send(ScreenInput(env.screenId, elementId, it)) }
    }
}

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
        val color = when {
            !active -> WidgetColors.LABEL_DISABLED
            index < 0 -> WidgetColors.LABEL_DISABLED
            else -> -1
        }
        val arrowSpace = ARROW_AREA
        if (text != null) {
            graphics.enableScissor(x + 4, y, x + width - arrowSpace, y + height)
            graphics.text(env.font, text, x + 5, y + (height - env.font.lineHeight) / 2 + 1, color, true)
            graphics.disableScissor()
        }
        extractArrow(graphics, x + width - arrowSpace / 2 - 4, y + height / 2 - 2, open, if (active) -1 else WidgetColors.LABEL_DISABLED)
        handleCursor(graphics)
    }

    override fun handleCursor(graphics: GuiGraphicsExtractor) {
        if (isHovered) graphics.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    companion object {
        private const val ARROW_AREA = 14
        private const val NONE = "\u0000"

        private fun encode(value: String?): String = value ?: NONE

        /** Small triangle (7 px wide, 4 rows) pointing down, or up when [up]. */
        fun extractArrow(graphics: GuiGraphicsExtractor, left: Int, top: Int, up: Boolean, color: Int) {
            for (row in 0 until 4) {
                val inset = if (up) 3 - row else row
                graphics.fill(left + inset, top + row, left + 7 - inset, top + row + 1, color)
            }
        }
    }
}
