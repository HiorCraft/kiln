package dev.kiln.protocol

/**
 * A node of the declarative GUI tree. Every element has a stable [id] (unique within its tree) so the client can
 * keep focus, text content, input values and scroll position across [UpdateScreen]s, and [bounds] relative to its
 * parent (for children of a [ScrollPanel]: relative to the top of its scrollable content). The root element is
 * centered on the screen, or placed at its own x/y for [OpenScreen.fullscreen] screens.
 *
 * Text values ([Text.text], [Button.label], tooltips, placeholders, labels) are Minecraft text components
 * serialized as JSON.
 *
 * Wire layout of every element: `varint type`, `string id`, bounds, `nullable string tooltip`, type-specific fields.
 */
public sealed interface Element {
    public val id: String
    public val bounds: Bounds

    /**
     * Component JSON shown while the mouse is over the element (line breaks via `\n`). For [ItemDisplay] /
     * [ItemSlot] it is appended below the vanilla item tooltip.
     */
    public val tooltip: String?
}

/** An element with children: [Panel], [ScrollPanel] and [Modal]. Children are drawn after (above) their parent. */
public sealed interface Container : Element {
    public val children: List<Element>
}

/**
 * Plain container. With [clickable] = `true` a click anywhere on the panel that is not consumed by an interactive
 * child sends [ScreenClick] (tiles, tabs). Background precedence: [selected] -> [selectedBackground] (falling back
 * to [background]), otherwise hovered -> [hoverBackground] (falling back to [background]).
 */
public data class Panel(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val background: Color? = null,
    val texture: ResourceId? = null,
    val border: Color? = null,
    val hoverBackground: Color? = null,
    val selectedBackground: Color? = null,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    override val tooltip: String? = null,
) : Container

/**
 * Vertically scrollable container. Children are laid out in a content area of `bounds.width` x [contentHeight]
 * (their bounds are relative to its top-left corner); the client clips them to [bounds] (scissor), scrolls with the
 * mouse wheel / a scrollbar and keeps the scroll offset per element id across updates (clamped when the content
 * shrinks). [contentHeight] smaller than `bounds.height` simply means nothing to scroll.
 */
public data class ScrollPanel(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val contentHeight: Int,
    val background: Color? = null,
    val border: Color? = null,
    /** Draw a scrollbar at the right edge (inside [bounds]) when the content is taller than the panel. */
    val scrollbar: Boolean = true,
    override val tooltip: String? = null,
) : Container

/**
 * Dialog drawn above everything else (except an open [Dropdown] list). Only allowed as a **direct child of the
 * root**. While at least one modal is present, the client dims the whole window with [dimColor] below the topmost
 * modal (the last [Modal] among the root's children), blocks input to everything outside it and sends
 * [ModalDismiss] (instead of closing the screen) when the player presses Escape. The server decides what happens
 * (usually: re-render without the modal).
 */
public data class Modal(
    override val id: String,
    override val bounds: Bounds,
    override val children: List<Element> = emptyList(),
    val background: Color? = Color(0xF0101010.toInt()),
    val border: Color? = Color(0xFF808080.toInt()),
    val dimColor: Color = Color(0xA0000000.toInt()),
    override val tooltip: String? = null,
) : Container

public data class Text(
    override val id: String,
    override val bounds: Bounds,
    /** Component JSON. */
    val text: String,
    val color: Color = Color.WHITE,
    val align: TextAlign = TextAlign.LEFT,
    val shadow: Boolean = true,
    override val tooltip: String? = null,
) : Element

/** Clicking sends [ScreenClick]. */
public data class Button(
    override val id: String,
    override val bounds: Bounds,
    /** Component JSON. */
    val label: String,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element

/**
 * Draws the region ([u], [v], [regionWidth] x [regionHeight]) of a [textureWidth] x [textureHeight] texture,
 * scaled to [bounds]. Textures come from client extensions or vanilla.
 */
public data class Image(
    override val id: String,
    override val bounds: Bounds,
    val texture: ResourceId,
    val u: Float,
    val v: Float,
    val regionWidth: Int,
    val regionHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    override val tooltip: String? = null,
) : Element

/** Renders an item (scaled to [bounds]) with the vanilla tooltip on hover (unless [showItemTooltip] is `false`). */
public data class ItemDisplay(
    override val id: String,
    override val bounds: Bounds,
    val item: ItemStackData,
    val showItemTooltip: Boolean = true,
    override val tooltip: String? = null,
) : Element

/**
 * Inventory-like slot: optional item, hover highlight, optional selection frame. Clicking (any button, with
 * modifiers) sends [ScreenClick]; the server performs all item movements itself, the client never moves items.
 * An empty slot shows [placeholderSprite] (a GUI sprite id, e.g. `minecraft:container/slot/sword`), if set.
 */
public data class ItemSlot(
    override val id: String,
    override val bounds: Bounds,
    val item: ItemStackData? = null,
    val placeholderSprite: ResourceId? = null,
    /** Draw the vanilla slot background (`minecraft:container/slot`) behind the item. */
    val slotBackground: Boolean = true,
    /** Draw the vanilla white hover highlight. */
    val highlight: Boolean = true,
    val selected: Boolean = false,
    /** Disabled slots are drawn greyed out and send no clicks. */
    val enabled: Boolean = true,
    val showItemTooltip: Boolean = true,
    override val tooltip: String? = null,
) : Element

public data class ProgressBar(
    override val id: String,
    override val bounds: Bounds,
    /** 0.0 .. 1.0 */
    val value: Float,
    val color: Color = Color.rgb(0x55FF55),
    val background: Color = Color.rgb(0x333333),
    override val tooltip: String? = null,
) : Element

/** Numeric constraint of a [TextField]: the client only accepts numbers in [min]..[max] (whole numbers if [integer]). */
public data class NumericRange(val min: Double, val max: Double, val integer: Boolean = true) {
    /** `true` if [value] is a valid number for this range (decimal notation, `.` as separator). */
    public fun accepts(value: String): Boolean {
        val number = if (integer) value.toLongOrNull()?.toDouble() else value.toDoubleOrNull()
        return number != null && number.isFinite() && number >= min && number <= max
    }
}

/**
 * Editable text. The client applies [value] when the element is new or the server changed the value;
 * otherwise the local content is kept. Edits are reported via [ScreenInput]. With [numeric] set, the client only
 * allows numeric input and reports only values the range [accepts][NumericRange.accepts] (or the empty string).
 */
public data class TextField(
    override val id: String,
    override val bounds: Bounds,
    val value: String = "",
    /** Component JSON. */
    val placeholder: String? = null,
    val maxLength: Int = 256,
    val numeric: NumericRange? = null,
    override val tooltip: String? = null,
) : Element

public data class DropdownOption(
    val value: String,
    /** Component JSON. */
    val label: String,
)

/**
 * Selection box. Clicking opens the option list, which the client draws as an overlay above everything (modals
 * included). Choosing an option sends [ScreenInput] with its [DropdownOption.value]. Like a [TextField], the client
 * keeps the local choice across updates until the server changes [selected].
 */
public data class Dropdown(
    override val id: String,
    override val bounds: Bounds,
    val options: List<DropdownOption>,
    /** Value of the selected option, or `null` for none (shows [placeholder]). Must be one of [options]. */
    val selected: String? = null,
    /** Component JSON. */
    val placeholder: String? = null,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element

public enum class CheckboxStyle { CHECKBOX, TOGGLE }

/** Boolean input; toggling sends [ScreenInput] with `"true"` / `"false"`. */
public data class Checkbox(
    override val id: String,
    override val bounds: Bounds,
    val checked: Boolean,
    /** Component JSON, drawn next to the box. */
    val label: String? = null,
    val style: CheckboxStyle = CheckboxStyle.CHECKBOX,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element

/**
 * Number slider over [min]..[max] snapping to [step] (`0` = continuous). The client sends [ScreenInput] with the
 * value formatted as a plain decimal number (`Double.toString`-compatible, integers without fraction when
 * [step] is whole) while dragging (throttled) and on release. [label] (component JSON) is drawn as `label: value`.
 */
public data class Slider(
    override val id: String,
    override val bounds: Bounds,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
    val label: String? = null,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** `true` if [candidate] lies in [min]..[max] and on the [step] grid (with a small tolerance). */
    public fun accepts(candidate: Double): Boolean {
        if (!candidate.isFinite() || candidate < min || candidate > max) return false
        if (step <= 0.0) return true
        val steps = (candidate - min) / step
        return kotlin.math.abs(steps - kotlin.math.round(steps)) <= 1e-6 || candidate == max
    }
}

/** Rendered by a client extension registered for [type]; unknown types are drawn as a placeholder. */
public data class Custom(
    override val id: String,
    override val bounds: Bounds,
    val type: ResourceId,
    val properties: Map<String, String> = emptyMap(),
    override val tooltip: String? = null,
) : Element

/** Depth-first (pre-order) traversal of [this] element and all descendants. */
public fun Element.walk(): Sequence<Element> = sequence {
    yield(this@walk)
    val self = this@walk
    if (self is Container) self.children.forEach { yieldAll(it.walk()) }
}

/** Finds the element with [id] in this tree. */
public fun Element.find(id: String): Element? = walk().firstOrNull { it.id == id }

/** The topmost (last) [Modal] among the root's children, or `null` if no modal is open. */
public fun Element.topModal(): Modal? = (this as? Container)?.children?.lastOrNull { it is Modal } as Modal?
