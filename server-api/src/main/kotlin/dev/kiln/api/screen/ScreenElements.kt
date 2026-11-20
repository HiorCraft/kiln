package dev.kiln.api.screen

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.inventory.ItemStack

/**
 * Rendered, immutable GUI tree produced by the screen DSL. The implementation converts it to the wire format and
 * keeps the handlers server-side; clients only ever see data.
 *
 * Coordinates are GUI pixels relative to the parent element (inside a [Layout.Absolute] panel; inside a
 * [ScrollPanelElement] relative to the top of its scrollable content). The root panel is centered on the client's
 * screen, or fills the window for fullscreen screens.
 */
public sealed interface ScreenElement {
    /** Stable id; explicit ids keep client state (focus, text, scroll) across updates, auto ids start with `auto:`. */
    public val id: String
    public val x: Int
    public val y: Int
    public val width: Int
    public val height: Int

    /** Shown while hovering the element; for items appended below the vanilla item tooltip. */
    public val tooltip: Component?
}

/** An element with children. */
public sealed interface ContainerElement : ScreenElement {
    public val children: List<ScreenElement>
    public val layout: Layout
}

/**
 * The client's GUI-scaled window size in GUI pixels (what `width`/`height` of a fullscreen root are).
 * Before the client reported it, [DEFAULT] is used.
 */
public data class Viewport(val width: Int, val height: Int) {
    public companion object {
        /** 1920x1080 at GUI scale 4. */
        public val DEFAULT: Viewport = Viewport(480, 270)
    }
}

/** How a panel positions its children. */
public sealed interface Layout {
    /** Children use their own x/y. */
    public data object Absolute : Layout

    /** Children are stacked top to bottom; a child's y is ignored, its x is an extra indent. */
    public data class Column(val gap: Int = 2, val padding: Int = 4) : Layout

    /** Children are placed left to right; a child's x is ignored, its y is an extra offset. */
    public data class Row(val gap: Int = 2, val padding: Int = 4) : Layout
}

public enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * An item to show: either just a type ([OfType], works without a running server, e.g. in tests) or a full
 * [ItemStack] ([OfStack]) with all its data components (name, lore, enchantments, item model, ...).
 * [count] is the displayed amount, 1..1,000,000,000 (above 99 drawn as abbreviated text); it is independent of the
 * stack's own amount.
 */
public sealed interface ScreenItem {
    public val count: Int

    public data class OfType(val type: Key, override val count: Int = 1) : ScreenItem {
        init {
            requireCount(count)
        }
    }

    /** Use [ScreenItem.of], which copies the stack; do not mutate [stack] afterwards. */
    public data class OfStack(val stack: ItemStack, override val count: Int) : ScreenItem {
        init {
            requireCount(count)
            require(!stack.isEmpty) { "Empty item stacks cannot be displayed" }
        }
    }

    public companion object {
        public const val MAX_COUNT: Int = 1_000_000_000

        /** A copy of [stack]; [count] defaults to the stack amount. */
        @JvmStatic
        public fun of(stack: ItemStack, count: Int = stack.amount): ScreenItem = OfStack(stack.clone(), count)

        @JvmStatic
        public fun of(type: Key, count: Int = 1): ScreenItem = OfType(type, count)

        /** `null` for `null`, air or empty stacks. */
        @JvmStatic
        public fun ofNullable(stack: ItemStack?, count: Int? = null): ScreenItem? =
            stack?.takeUnless { it.isEmpty }?.let { of(it, count ?: it.amount) }

        private fun requireCount(count: Int) {
            require(count in 1..MAX_COUNT) { "Item count out of range: $count" }
        }
    }
}

public data class PanelElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val background: Color?,
    val texture: Key?,
    val border: Color?,
    val hoverBackground: Color? = null,
    val selectedBackground: Color? = null,
    val selected: Boolean = false,
    override val tooltip: Component? = null,
    /** Non-null makes the panel clickable (tiles, tabs). */
    val onClick: ((ClickContext) -> Unit)? = null,
) : ContainerElement

/** Vertically scrolling container; [contentHeight] `null` = computed from the children (bottom edge + padding). */
public data class ScrollPanelElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val contentHeight: Int?,
    val background: Color?,
    val border: Color?,
    val scrollbar: Boolean,
    override val tooltip: Component? = null,
) : ContainerElement

/** Dialog above everything else; only allowed directly in the screen root. */
public data class ModalElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    override val children: List<ScreenElement>,
    override val layout: Layout,
    val background: Color?,
    val border: Color?,
    val dimColor: Color,
    val onDismiss: ((ModalDismissContext) -> Unit)?,
    override val tooltip: Component? = null,
) : ContainerElement

public data class TextElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val text: Component,
    val color: Color,
    val align: TextAlign,
    val shadow: Boolean,
    override val tooltip: Component? = null,
) : ScreenElement

public data class ButtonElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val label: Component,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onClick: ((ClickContext) -> Unit)?,
) : ScreenElement

/** Region [u]/[v] ([regionWidth] x [regionHeight]) of a [textureWidth] x [textureHeight] texture, scaled to the bounds. */
public data class ImageElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val texture: Key,
    val u: Float,
    val v: Float,
    val regionWidth: Int,
    val regionHeight: Int,
    val textureWidth: Int,
    val textureHeight: Int,
    override val tooltip: Component? = null,
) : ScreenElement

public data class ItemElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val item: ScreenItem,
    val showItemTooltip: Boolean = true,
    override val tooltip: Component? = null,
) : ScreenElement

/** Clickable inventory-like slot. Item movements are entirely up to the click handler (server-side). */
public data class ItemSlotElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val item: ScreenItem?,
    val placeholderSprite: Key?,
    val slotBackground: Boolean,
    val highlight: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val showItemTooltip: Boolean,
    override val tooltip: Component?,
    val onClick: ((ClickContext) -> Unit)?,
) : ScreenElement

public data class ProgressBarElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    /** 0.0 .. 1.0 */
    val value: Float,
    val color: Color,
    val background: Color,
    override val tooltip: Component? = null,
) : ScreenElement

/** Numeric constraint of a text field: only numbers in [min]..[max], whole numbers if [integer]. */
public data class NumericRange(val min: Double, val max: Double, val integer: Boolean = true) {
    init {
        require(min.isFinite() && max.isFinite() && min <= max) { "Invalid numeric range $min..$max" }
    }
}

public data class TextFieldElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    /** Initial value; the client keeps the player's edits across updates as long as this value is unchanged. */
    val value: String,
    val placeholder: Component?,
    val maxLength: Int,
    val onInput: ((InputContext) -> Unit)?,
    val numeric: NumericRange? = null,
    override val tooltip: Component? = null,
) : ScreenElement

public data class DropdownOption(val value: String, val label: Component)

public data class DropdownElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val options: List<DropdownOption>,
    /** Value of the selected option or `null`; like a text field value, the client keeps a local choice until this changes. */
    val selected: String?,
    val placeholder: Component?,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement

public enum class CheckboxStyle { CHECKBOX, TOGGLE }

public data class CheckboxElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val checked: Boolean,
    val label: Component?,
    val style: CheckboxStyle,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement

public data class SliderElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val min: Double,
    val max: Double,
    /** Snap step, `0` = continuous. */
    val step: Double,
    val value: Double,
    val label: Component?,
    val enabled: Boolean,
    override val tooltip: Component?,
    val onChange: ((InputContext) -> Unit)?,
) : ScreenElement

/** Widget rendered by a client extension registered for [type]; clients without it draw a placeholder. */
public data class CustomElement(
    override val id: String,
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val type: Key,
    val properties: Map<String, String>,
    val onClick: ((ClickContext) -> Unit)?,
    override val tooltip: Component? = null,
) : ScreenElement

/** Result of evaluating a [ScreenDefinition]: the root panel plus screen-level handlers. */
public class RenderedScreen(
    public val root: PanelElement,
    public val onClose: ((CloseContext) -> Unit)?,
    /** The root fills the client window ([ScreenDefinition.fullscreen]). */
    public val fullscreen: Boolean = false,
)
