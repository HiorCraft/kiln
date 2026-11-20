package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * A reusable screen template: size plus a content lambda. The lambda is evaluated on [render] (when the screen is
 * opened, on every [ScreenHandle.refresh] and automatically when the client's [Viewport] changes), so it can simply
 * read your current state:
 *
 * ```
 * var progress = 0f
 * val factory = screen(176, 120) {
 *     layout = Layout.Column()
 *     text("Factory", width = 168, align = TextAlign.CENTER)
 *     progressBar(progress, width = 168)
 *     button("start", "Start") { onClick { ctx -> progress = 0.1f; ctx.screen.refresh() } }
 *     onClose { ctx -> ctx.player.sendMessage("closed: ${ctx.reason}") }
 * }
 * val shop = screen(fullscreen = true) {
 *     // width/height == viewport.width/height
 *     scrollPanel("offers", x = 10, y = 30, width = width - 20, height = height - 40) { ... }
 * }
 * val handle = player.openScreen(factory)
 * ```
 *
 * Ids: interactive elements (button, input, slot, scroll panel, modal, clickable panel, custom widget) take an
 * explicit id, which must be stable across updates because handlers and client state are bound to it. Passive
 * elements get an `auto:<n>` id unless you pass one (pass one if the element appears conditionally and you care
 * about client-side state).
 */
public class ScreenDefinition(
    /** Root size for fixed screens; ignored (0) for [fullscreen] screens. */
    public val width: Int,
    public val height: Int,
    /** The root always has the size of the client's [Viewport] and is placed at the window's top-left corner. */
    public val fullscreen: Boolean = false,
    public val content: ScreenBuilder.() -> Unit,
) {
    init {
        require(width in 0..MAX_SIZE && height in 0..MAX_SIZE) { "Screen size out of range: ${width}x$height" }
    }

    /** Evaluates the content for [viewport]. Throws [IllegalArgumentException] for invalid ids or values. */
    public fun render(viewport: Viewport = Viewport.DEFAULT): RenderedScreen {
        val w = if (fullscreen) viewport.width.coerceIn(1, MAX_SIZE) else width
        val h = if (fullscreen) viewport.height.coerceIn(1, MAX_SIZE) else height
        return ScreenBuilder(w, h, viewport).apply(content).build(fullscreen)
    }

    /** Same size, other content. */
    public fun withContent(content: ScreenBuilder.() -> Unit): ScreenDefinition = ScreenDefinition(width, height, fullscreen, content)

    internal companion object {
        const val MAX_SIZE = 8_192
    }
}

/** Defines a screen of [width] x [height] GUI pixels, centered on the client's window. */
public fun screen(width: Int, height: Int, content: ScreenBuilder.() -> Unit): ScreenDefinition =
    ScreenDefinition(width, height, false, content)

/**
 * Defines a fullscreen screen: the root is as large as the client's GUI-scaled window ([ScreenBuilder.viewport])
 * and is re-rendered automatically when that changes. `fullscreen` must be `true`; use `screen(width, height)` for
 * fixed-size screens.
 */
public fun screen(fullscreen: Boolean, content: ScreenBuilder.() -> Unit): ScreenDefinition {
    require(fullscreen) { "Use screen(width, height) { } for fixed-size screens" }
    return ScreenDefinition(0, 0, true, content)
}

/** Builder for elements inside a container (panel, scroll panel, modal or the screen root). */
@KilnDsl
public open class ContainerBuilder internal constructor(internal val ids: IdAllocator) {
    private val children = mutableListOf<ScreenElement>()

    /** How children are positioned; see [Layout]. */
    public var layout: Layout = Layout.Absolute
    public var background: Color? = null

    /** Panels only. */
    public var texture: Key? = null
    public var border: Color? = null

    /** Tooltip of the container itself. */
    public var tooltip: Component? = null

    internal fun children(): List<ScreenElement> = children.toList()

    internal fun add(element: ScreenElement) {
        children += element
    }

    public fun panel(
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
        id: String? = null,
        content: PanelBuilder.() -> Unit,
    ) {
        val panelId = ids.passive(id)
        val builder = PanelBuilder(ids).apply(content)
        require(builder.clickHandler == null || id != null) { "A clickable panel needs an explicit id" }
        add(builder.build(panelId, x, y, width, height))
    }

    /**
     * A vertically scrollable area of [width] x [height]; children are positioned in a content area of
     * [width] x [contentHeight] (`null` = up to the bottom edge of the lowest child plus the layout padding).
     * The client keeps the scroll position per [id] across updates.
     */
    public fun scrollPanel(
        id: String,
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
        contentHeight: Int? = null,
        content: ScrollPanelBuilder.() -> Unit,
    ) {
        val panelId = ids.explicit(id)
        require(contentHeight == null || contentHeight in 0..MAX_SCROLL_CONTENT_HEIGHT) { "contentHeight out of range: $contentHeight" }
        add(ScrollPanelBuilder(ids).apply(content).build(panelId, x, y, width, height, contentHeight))
    }

    /** Text from a component. `width = 0` sizes the element to the text. */
    public fun text(
        text: Component,
        x: Int = 0,
        y: Int = 0,
        width: Int = 0,
        height: Int = 9,
        color: Color = Color.WHITE,
        align: TextAlign = TextAlign.LEFT,
        shadow: Boolean = true,
        id: String? = null,
        tooltip: Component? = null,
    ) {
        add(TextElement(ids.passive(id), x, y, width, height, text, color, align, shadow, tooltip))
    }

    /** Plain text. */
    public fun text(
        text: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 0,
        height: Int = 9,
        color: Color = Color.WHITE,
        align: TextAlign = TextAlign.LEFT,
        shadow: Boolean = true,
        id: String? = null,
        tooltip: Component? = null,
    ): Unit = text(Component.text(text), x, y, width, height, color, align, shadow, id, tooltip)

    public fun button(
        id: String,
        label: Component,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: ButtonBuilder.() -> Unit = {},
    ) {
        val b = ButtonBuilder().apply(content)
        add(ButtonElement(ids.explicit(id), x, y, width, height, label, b.enabled, b.tooltip, b.clickHandler))
    }

    public fun button(
        id: String,
        label: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: ButtonBuilder.() -> Unit = {},
    ): Unit = button(id, Component.text(label), x, y, width, height, content)

    /** Draws a texture region; defaults draw the whole texture of size [width] x [height]. */
    public fun image(
        texture: Key,
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
        u: Float = 0f,
        v: Float = 0f,
        regionWidth: Int = width,
        regionHeight: Int = height,
        textureWidth: Int = width,
        textureHeight: Int = height,
        id: String? = null,
        tooltip: Component? = null,
    ) {
        add(ImageElement(ids.passive(id), x, y, width, height, texture, u, v, regionWidth, regionHeight, textureWidth, textureHeight, tooltip))
    }

    /**
     * An item icon (scaled to [size]) with vanilla tooltip. [tooltip] is appended below the item tooltip
     * (or shown alone if [showItemTooltip] is `false`).
     */
    public fun item(
        item: ScreenItem,
        x: Int = 0,
        y: Int = 0,
        size: Int = 16,
        showItemTooltip: Boolean = true,
        tooltip: Component? = null,
        id: String? = null,
    ) {
        add(ItemElement(ids.passive(id), x, y, size, size, item, showItemTooltip, tooltip))
    }

    /** An item type icon, e.g. `item(Key.key("diamond"), count = 3)`. */
    public fun item(item: Key, x: Int = 0, y: Int = 0, count: Int = 1, size: Int = 16, id: String? = null, tooltip: Component? = null): Unit =
        item(ScreenItem.of(item, count), x, y, size, true, tooltip, id)

    /** A full item stack (name, lore, glint, model, ...); [count] defaults to the stack amount and may exceed 99. */
    public fun item(
        stack: ItemStack,
        x: Int = 0,
        y: Int = 0,
        count: Int = stack.amount,
        size: Int = 16,
        showItemTooltip: Boolean = true,
        tooltip: Component? = null,
        id: String? = null,
    ): Unit = item(ScreenItem.of(stack, count), x, y, size, showItemTooltip, tooltip, id)

    /** A clickable item slot ([size] x [size], vanilla slots are 18). */
    public fun itemSlot(id: String, x: Int = 0, y: Int = 0, size: Int = 18, content: ItemSlotBuilder.() -> Unit = {}) {
        val b = ItemSlotBuilder().apply(content)
        add(
            ItemSlotElement(
                ids.explicit(id), x, y, size, size, b.item, b.placeholderSprite, b.slotBackground, b.highlight,
                b.selected, b.enabled, b.showItemTooltip, b.tooltip, b.clickHandler,
            ),
        )
    }

    /**
     * The player's inventory as a 9-column slot grid: main inventory (slots 9..35, three rows), then the hotbar
     * (slots 0..8) below a gap. Element ids: the grid panel is [idPrefix], each slot `"$idPrefix/<slot>"`.
     * Items are read at render time; clicks only report which slot was clicked, all item movements stay in your
     * handler.
     */
    public fun inventoryGrid(
        player: Player,
        x: Int = 0,
        y: Int = 0,
        idPrefix: String = "inventory",
        slotSize: Int = 18,
        content: InventoryGridBuilder.() -> Unit = {},
    ): Unit = inventoryGrid({ player.inventory.getItem(it) }, x, y, idPrefix, slotSize, content)

    /** Like the [Player] variant, reading slot contents (0..35, player inventory numbering) from [items]. */
    public fun inventoryGrid(
        items: (slot: Int) -> ItemStack?,
        x: Int = 0,
        y: Int = 0,
        idPrefix: String = "inventory",
        slotSize: Int = 18,
        content: InventoryGridBuilder.() -> Unit = {},
    ) {
        val grid = InventoryGridBuilder().apply(content)
        val rows = if (grid.includeHotbar) 4 else 3
        val height = 3 * slotSize + if (grid.includeHotbar) grid.hotbarGap + slotSize else 0
        panel(x, y, 9 * slotSize, height, id = idPrefix) {
            for (index in 0 until rows * 9) {
                val row = index / 9
                val column = index % 9
                val slot = if (row < 3) 9 + index else column
                val slotY = row * slotSize + if (row == 3) grid.hotbarGap else 0
                val stack = items(slot)?.takeUnless { it.isEmpty }
                itemSlot("$idPrefix/$slot", column * slotSize, slotY, slotSize) {
                    item = ScreenItem.ofNullable(stack)
                    grid.customizer?.invoke(this, slot, stack)
                    val handler = grid.clickHandler
                    if (handler != null && clickHandler == null) {
                        onClick { ctx -> handler(InventorySlotClickContext(ctx, slot, stack)) }
                    }
                }
            }
        }
    }

    public fun progressBar(
        value: Float,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 5,
        color: Color = Color.fromRGB(0x55FF55),
        background: Color = Color.fromRGB(0x333333),
        id: String? = null,
        tooltip: Component? = null,
    ) {
        add(ProgressBarElement(ids.passive(id), x, y, width, height, value.coerceIn(0f, 1f), color, background, tooltip))
    }

    public fun textField(
        id: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: TextFieldBuilder.() -> Unit = {},
    ) {
        val b = TextFieldBuilder().apply(content)
        require(b.maxLength in 1..1024) { "maxLength out of range: ${b.maxLength}" }
        require(b.value.length <= b.maxLength) { "Text field value longer than maxLength" }
        add(TextFieldElement(ids.explicit(id), x, y, width, height, b.value, b.placeholder, b.maxLength, b.inputHandler, b.numeric, b.tooltip))
    }

    /** Selection box; the option list opens as an overlay above everything. */
    public fun dropdown(
        id: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: DropdownBuilder.() -> Unit,
    ) {
        val b = DropdownBuilder().apply(content)
        require(b.options.map { it.value }.toSet().size == b.options.size) { "Duplicate dropdown option values" }
        require(b.selected == null || b.options.any { it.value == b.selected }) { "Selected option '${b.selected}' does not exist" }
        add(DropdownElement(ids.explicit(id), x, y, width, height, b.options.toList(), b.selected, b.placeholder, b.enabled, b.tooltip, b.changeHandler))
    }

    public fun checkbox(
        id: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: CheckboxBuilder.() -> Unit = {},
    ) {
        val b = CheckboxBuilder().apply(content)
        add(CheckboxElement(ids.explicit(id), x, y, width, height, b.checked, b.label, b.style, b.enabled, b.tooltip, b.changeHandler))
    }

    public fun slider(
        id: String,
        x: Int = 0,
        y: Int = 0,
        width: Int = 100,
        height: Int = 20,
        content: SliderBuilder.() -> Unit,
    ) {
        val b = SliderBuilder().apply(content)
        require(b.min.isFinite() && b.max.isFinite() && b.min <= b.max) { "Invalid slider range ${b.min}..${b.max}" }
        require(b.step.isFinite() && b.step >= 0.0) { "Invalid slider step ${b.step}" }
        add(
            SliderElement(
                ids.explicit(id), x, y, width, height, b.min, b.max, b.step, b.value.coerceIn(b.min, b.max),
                b.label, b.enabled, b.tooltip, b.changeHandler,
            ),
        )
    }

    /** A widget rendered by a client extension registered for [type]. */
    public fun custom(
        id: String,
        type: Key,
        x: Int = 0,
        y: Int = 0,
        width: Int,
        height: Int,
        content: CustomBuilder.() -> Unit = {},
    ) {
        val b = CustomBuilder().apply(content)
        add(CustomElement(ids.explicit(id), x, y, width, height, type, b.properties.toMap(), b.clickHandler, b.tooltip))
    }

    internal companion object {
        const val MAX_SCROLL_CONTENT_HEIGHT = 65_536
    }
}

/** Content of a nested panel. */
@KilnDsl
public class PanelBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    /** Background while hovered (falls back to [background]). */
    public var hoverBackground: Color? = null

    /** Background while [selected] (falls back to [background]). */
    public var selectedBackground: Color? = null
    public var selected: Boolean = false
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    /** Makes the panel clickable (tiles, tabs); requires an explicit panel id. */
    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int): PanelElement =
        PanelElement(
            id, x, y, width, height, children(), layout, background, texture, border,
            hoverBackground, selectedBackground, selected, tooltip, clickHandler,
        )
}

/** Content of a scroll panel ([texture] is ignored). */
@KilnDsl
public class ScrollPanelBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    public var scrollbar: Boolean = true

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int, contentHeight: Int?): ScrollPanelElement =
        ScrollPanelElement(id, x, y, width, height, children(), layout, contentHeight, background, border, scrollbar, tooltip)
}

/** Content of a modal dialog ([texture] is ignored). */
@KilnDsl
public class ModalBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    /** Color drawn over the whole window below the modal. */
    public var dimColor: Color = Color.fromARGB(0xA0, 0, 0, 0)
    internal var dismissHandler: ((ModalDismissContext) -> Unit)? = null

    init {
        background = Color.fromARGB(0xF0, 0x10, 0x10, 0x10)
        border = Color.fromRGB(0x808080)
    }

    /**
     * Called when the player presses Escape while this modal is the topmost one. The screen stays open; usually you
     * re-render without the modal. Without a handler Escape does nothing while the modal is shown.
     */
    public fun onDismiss(handler: (ModalDismissContext) -> Unit) {
        dismissHandler = handler
    }

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int): ModalElement =
        ModalElement(id, x, y, width, height, children(), layout, background, border, dimColor, dismissHandler, tooltip)
}

/** Content of the screen root panel plus screen-level handlers. */
@KilnDsl
public class ScreenBuilder internal constructor(
    /** Root width; equals `viewport.width` for fullscreen screens. */
    public val width: Int,
    public val height: Int,
    /** The client's GUI-scaled window size ([Viewport.DEFAULT] until the client reported it). */
    public val viewport: Viewport = Viewport(width.coerceAtLeast(1), height.coerceAtLeast(1)),
) : ContainerBuilder(IdAllocator()) {
    private var closeHandler: ((CloseContext) -> Unit)? = null

    /** Called once when the screen closes, for whatever [CloseReason]. */
    public fun onClose(handler: (CloseContext) -> Unit) {
        closeHandler = handler
    }

    /**
     * A modal dialog above everything else, centered in the root unless [x]/[y] are given. Several modals stack;
     * the last one is on top and the only one receiving input. Remove it by re-rendering without it.
     */
    public fun modal(
        id: String,
        width: Int,
        height: Int,
        x: Int? = null,
        y: Int? = null,
        content: ModalBuilder.() -> Unit,
    ) {
        val modalId = ids.explicit(id)
        val builder = ModalBuilder(ids).apply(content)
        add(builder.build(modalId, x ?: (this.width - width) / 2, y ?: (this.height - height) / 2, width, height))
    }

    internal fun build(fullscreen: Boolean = false): RenderedScreen = RenderedScreen(
        PanelElement(ROOT_ID, 0, 0, width, height, children(), layout, background, texture, border, tooltip = tooltip),
        closeHandler,
        fullscreen,
    )

    public companion object {
        /** Id of the root panel. */
        public const val ROOT_ID: String = "root"
    }
}

@KilnDsl
public class ButtonBuilder internal constructor() {
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}

@KilnDsl
public class ItemSlotBuilder internal constructor() {
    /** Shown item; `null` = empty slot (showing [placeholderSprite] if set). */
    public var item: ScreenItem? = null

    /** GUI sprite for an empty slot, e.g. `Key.key("container/slot/sword")`. */
    public var placeholderSprite: Key? = null
    public var slotBackground: Boolean = true
    public var highlight: Boolean = true
    public var selected: Boolean = false
    public var enabled: Boolean = true
    public var showItemTooltip: Boolean = true

    /** Appended below the item tooltip. */
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    /** Sets [item] from a stack (`null`/air = empty); [count] defaults to the stack amount. */
    public fun item(stack: ItemStack?, count: Int? = null) {
        item = ScreenItem.ofNullable(stack, count)
    }

    /** Any mouse button; check [ClickContext.button] and [ClickContext.shift]. */
    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}

@KilnDsl
public class InventoryGridBuilder internal constructor() {
    public var includeHotbar: Boolean = true

    /** Vertical gap between main inventory and hotbar, in GUI pixels. */
    public var hotbarGap: Int = 4
    internal var customizer: (ItemSlotBuilder.(slot: Int, item: ItemStack?) -> Unit)? = null
    internal var clickHandler: ((InventorySlotClickContext) -> Unit)? = null

    /** Customizes every slot (e.g. `enabled = item != null && sellable(item)`, `selected = slot == chosen`). */
    public fun slots(customizer: ItemSlotBuilder.(slot: Int, item: ItemStack?) -> Unit) {
        this.customizer = customizer
    }

    public fun onClick(handler: (InventorySlotClickContext) -> Unit) {
        clickHandler = handler
    }
}

@KilnDsl
public class TextFieldBuilder internal constructor() {
    public var value: String = ""
    public var placeholder: Component? = null
    public var maxLength: Int = 256
    public var tooltip: Component? = null
    internal var numeric: NumericRange? = null
    internal var inputHandler: ((InputContext) -> Unit)? = null

    /** Restricts input to numbers in [min]..[max] (whole numbers if [integer]); reported values are valid or "". */
    public fun numeric(min: Double, max: Double, integer: Boolean = true) {
        numeric = NumericRange(min, max, integer)
    }

    /** Called with the new content (throttled while typing, immediately on enter). */
    public fun onInput(handler: (InputContext) -> Unit) {
        inputHandler = handler
    }
}

@KilnDsl
public class DropdownBuilder internal constructor() {
    internal val options = mutableListOf<DropdownOption>()

    /** Value of the selected option, `null` = none (shows [placeholder]). */
    public var selected: String? = null
    public var placeholder: Component? = null
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    public fun option(value: String, label: Component) {
        options += DropdownOption(value, label)
    }

    public fun option(value: String, label: String): Unit = option(value, Component.text(label))

    /** Called with the chosen option value ([InputContext.value]). */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}

@KilnDsl
public class CheckboxBuilder internal constructor() {
    public var checked: Boolean = false
    public var label: Component? = null
    public var style: CheckboxStyle = CheckboxStyle.CHECKBOX
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    /** Called with the new state ([InputContext.checked]). */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}

@KilnDsl
public class SliderBuilder internal constructor() {
    public var min: Double = 0.0
    public var max: Double = 1.0

    /** Snap step, `0` = continuous. */
    public var step: Double = 0.0
    public var value: Double = 0.0

    /** Drawn as `label: value`. */
    public var label: Component? = null
    public var enabled: Boolean = true
    public var tooltip: Component? = null
    internal var changeHandler: ((InputContext) -> Unit)? = null

    /** Called with the new value ([InputContext.number]) while dragging (throttled) and on release. */
    public fun onChange(handler: (InputContext) -> Unit) {
        changeHandler = handler
    }
}

@KilnDsl
public class CustomBuilder internal constructor() {
    /** Free-form string properties passed to the client widget renderer. */
    public val properties: MutableMap<String, String> = LinkedHashMap()
    public var tooltip: Component? = null
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    public fun property(key: String, value: Any) {
        properties[key] = value.toString()
    }

    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }
}

/** Hands out element ids for one render pass and rejects invalid or duplicate explicit ids. */
internal class IdAllocator {
    private val used = HashSet<String>().apply { add(ScreenBuilder.ROOT_ID) }
    private var next = 0

    fun explicit(id: String): String {
        require(ID.matches(id)) { "Invalid element id '$id' (allowed: [A-Za-z0-9_.:/-], 1..64 chars)" }
        require(!id.startsWith(AUTO_PREFIX)) { "Element ids starting with '$AUTO_PREFIX' are reserved" }
        require(used.add(id)) { "Duplicate element id '$id'" }
        return id
    }

    fun passive(id: String?): String {
        if (id != null) return explicit(id)
        val auto = AUTO_PREFIX + next++
        used += auto
        return auto
    }

    private companion object {
        const val AUTO_PREFIX = "auto:"
        val ID = Regex("[A-Za-z0-9_.:/-]{1,64}")
    }
}
