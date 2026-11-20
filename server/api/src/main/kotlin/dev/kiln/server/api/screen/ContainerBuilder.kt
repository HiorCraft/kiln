package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

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
        require(contentHeight == null || contentHeight in 0..ScreenLimits.MAX_SCROLL_CONTENT_HEIGHT) { "contentHeight out of range: $contentHeight" }
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
        val rows = if (grid.includeHotbar) InventoryLayout.MAIN_ROWS + 1 else InventoryLayout.MAIN_ROWS
        val hotbarHeight = if (grid.includeHotbar) grid.hotbarGap + slotSize else 0
        val height = InventoryLayout.MAIN_ROWS * slotSize + hotbarHeight
        panel(x, y, InventoryLayout.COLUMNS * slotSize, height, id = idPrefix) {
            for (index in 0 until rows * InventoryLayout.COLUMNS) {
                val row = index / InventoryLayout.COLUMNS
                val column = index % InventoryLayout.COLUMNS
                val isHotbar = row == InventoryLayout.MAIN_ROWS
                val slot = if (isHotbar) column else InventoryLayout.FIRST_MAIN_SLOT + index
                val slotY = row * slotSize + if (isHotbar) grid.hotbarGap else 0
                inventorySlot(grid, "$idPrefix/$slot", slot, items(slot), column * slotSize, slotY, slotSize)
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
        require(b.maxLength in 1..ScreenLimits.MAX_TEXT_FIELD_LENGTH) { "maxLength out of range: ${b.maxLength}" }
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
}

/** One slot of [ContainerBuilder.inventoryGrid]; [contents] is the slot's stack at render time. */
private fun ContainerBuilder.inventorySlot(
    grid: InventoryGridBuilder,
    id: String,
    slot: Int,
    contents: ItemStack?,
    x: Int,
    y: Int,
    size: Int,
) {
    val stack = contents?.takeUnless { it.isEmpty }
    itemSlot(id, x, y, size) {
        item = ScreenItem.ofNullable(stack)
        grid.customizer?.invoke(this, slot, stack)
        val handler = grid.clickHandler
        if (handler != null && clickHandler == null) {
            onClick { ctx -> handler(InventorySlotClickContext(ctx, slot, stack)) }
        }
    }
}
