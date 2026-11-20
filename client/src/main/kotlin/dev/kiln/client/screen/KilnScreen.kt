package dev.kiln.client.screen

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.ext.ExtensionTextures
import dev.kiln.client.ext.GuiDrawTarget
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.lua.LuaExtension
import dev.kiln.protocol.Button
import dev.kiln.protocol.Checkbox
import dev.kiln.protocol.Custom
import dev.kiln.protocol.Dropdown
import dev.kiln.protocol.Element
import dev.kiln.protocol.Image
import dev.kiln.protocol.ItemDisplay
import dev.kiln.protocol.ItemSlot
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.Modal
import dev.kiln.protocol.ModalDismiss
import dev.kiln.protocol.Panel
import dev.kiln.protocol.ProgressBar
import dev.kiln.protocol.ScreenClick
import dev.kiln.protocol.ScreenClosed
import dev.kiln.protocol.ScrollPanel
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.Slider
import dev.kiln.protocol.Text
import dev.kiln.protocol.TextAlign
import dev.kiln.protocol.TextField
import dev.kiln.protocol.topModal
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.FormattedCharSequence
import org.slf4j.LoggerFactory
import java.util.Locale

/** What a [KilnScreen] needs from the runtime. */
interface ScreenHost {
    fun send(message: ServerboundMessage): Boolean

    /** A Lua widget instance for `Custom` type [type], or `null` (placeholder). */
    fun createWidget(type: Identifier): LuaExtension.WidgetInstance?

    val textures: ExtensionTextures
}

/**
 * Renders one server-described GUI tree (centered, or placed at its own x/y for fullscreen screens).
 *
 * - Elements are drawn in [TreeLayout.flatten] order; modals (and their dimming) after the rest of the tree, an open
 *   dropdown list after everything.
 * - Children of [ScrollPanel]s are shifted by the panel's scroll offset (kept per element id across updates) and
 *   clipped with a scissor; hit tests, hover and tooltips use the same [Geometry].
 * - Input is dispatched by the screen itself (topmost interactive element under the mouse, respecting clips and the
 *   topmost modal); only keyboard focus/Tab navigation uses vanilla's children list, which contains just the widgets
 *   that may receive input (those of the topmost modal while one is open).
 * - Updates ([update]) keep stateful objects per element id ([KeyedPool]), so focus, text, cursor, local input
 *   values, scroll positions and the state (`self`) of Lua widgets survive.
 * - `Custom` elements are drawn by the Lua widget of their type (`<ext>:<name>`), clipped to their bounds; an unknown
 *   type or a widget whose extension was unloaded draws a placeholder.
 */
class KilnScreen(
    val screenId: Int,
    root: Element,
    private val host: ScreenHost,
    fullscreen: Boolean = false,
) : Screen(Component.empty()) {

    private var root: Element = root

    /** Root placed at its own x/y and stretched to the window (protocol v2 `fullscreen`). */
    private var fullscreen: Boolean = fullscreen
    private var nodes: List<Node> = emptyList()
    private var placed: List<PlacedElement> = emptyList()
    private var geometry: List<Geometry> = emptyList()
    private var topModalId: String? = null
    private var originX = 0
    private var originY = 0

    /** Scroll panel index -> scroll panels whose scrollbar is drawn after this node (innermost first). */
    private var scrollbarsAfter: Map<Int, List<Int>> = emptyMap()

    /** Scroll offsets (GUI pixels) per scroll panel id, kept across updates. */
    private val scrollOffsets = HashMap<String, Double>()
    private var scrollbarDrag: ScrollbarDrag? = null

    private var dropdown: OpenDropdown? = null

    /** Custom element that received a mouse press (gets the matching release) and the button. */
    private var pressedCustom: Pair<CustomContext, Int>? = null

    private val items = ItemCache()

    /** Set when the screen goes away without the player closing it (replaced by the server, disconnect). */
    var closeSilently: Boolean = false

    private val env = object : WidgetEnv {
        override val screenId: Int get() = this@KilnScreen.screenId
        override val font: Font get() = this@KilnScreen.font
        override fun send(message: ServerboundMessage): Boolean = host.send(message)
        override fun toggleDropdown(head: DropdownHead) = this@KilnScreen.toggleDropdown(head)
        override fun isDropdownOpen(head: DropdownHead): Boolean = dropdown?.head === head
    }

    private val buttons = KeyedPool<Node, ServerButton>(
        idOf = { it.element.id },
        create = { ServerButton(env, it.element.id) },
        update = { button, node -> button.apply(node.element as Button, node.text) },
    )

    private val fields = KeyedPool<Node, ServerTextField>(
        idOf = { it.element.id },
        create = { node -> ServerTextField(env, node.element.id, (node.element as TextField).value) },
        update = { field, node -> field.apply(node.element as TextField, node.placeholder) },
    )

    private val checkboxes = KeyedPool<Node, ServerCheckbox>(
        idOf = { it.element.id },
        create = { ServerCheckbox(env, it.element.id, it.element as Checkbox) },
        update = { box, node -> box.apply(node.element as Checkbox, node.text) },
    )

    private val sliders = KeyedPool<Node, ServerSlider>(
        idOf = { it.element.id },
        create = { ServerSlider(env, it.element.id, it.element as Slider) },
        update = { slider, node -> slider.apply(node.element as Slider, node.text) },
    )

    private val dropdowns = KeyedPool<Node, DropdownHead>(
        idOf = { it.element.id },
        create = { DropdownHead(env, it.element.id, it.element as Dropdown) },
        update = { head, node -> head.apply(node.element as Dropdown, node.optionLabels, node.placeholder) },
    )

    private val customs = KeyedPool<Node, CustomContext>(
        idOf = { it.element.id },
        create = { CustomContext(it.element.id) },
        update = { ctx, node -> ctx.apply(node.element as Custom) },
    )

    init {
        setTree(root)
    }

    // ---- tree ---------------------------------------------------------------------------------------------

    /** Replaces the tree, keeping client state of elements whose id still exists. */
    fun update(newRoot: Element, fullscreen: Boolean = this.fullscreen) {
        this.fullscreen = fullscreen
        setTree(newRoot)
        if (minecraft.gui.screen() === this) layout()
    }

    private fun setTree(newRoot: Element) {
        root = newRoot
        placed = TreeLayout.flatten(newRoot)
        nodes = placed.mapIndexed { index, p -> Node(index, p) }
        topModalId = newRoot.topModal()?.id

        buttons.sync(nodes.filter { it.element is Button })
        fields.sync(nodes.filter { it.element is TextField })
        checkboxes.sync(nodes.filter { it.element is Checkbox })
        sliders.sync(nodes.filter { it.element is Slider })
        dropdowns.sync(nodes.filter { it.element is Dropdown })
        customs.sync(nodes.filter { it.element is Custom })
        items.retain(nodes.filter { it.element is ItemDisplay || it.element is ItemSlot }.map { it.element.id }.toSet())

        // scroll offsets: keep per id, clamp to the new content height
        val panels = nodes.filter { it.element is ScrollPanel }
        scrollOffsets.keys.retainAll(panels.map { it.element.id }.toSet())
        for (node in panels) {
            val e = node.element as ScrollPanel
            scrollOffsets[e.id]?.let { scrollOffsets[e.id] = ScrollMath.clamp(it, e.contentHeight, e.bounds.height) }
        }
        scrollbarsAfter = panels.groupBy({ it.placed.subtreeEnd }, { it.index }).mapValues { (_, list) -> list.sortedDescending() }
        scrollbarDrag?.let { drag -> if (panels.none { it.element.id == drag.panelId }) scrollbarDrag = null }

        // an open dropdown stays open if it still exists, is enabled and not blocked by a (new) modal
        dropdown?.let { open ->
            val node = nodes.firstOrNull { it.element.id == open.head.elementId && it.element is Dropdown }
            if (node == null || dropdowns[open.head.elementId] !== open.head || !(node.element as Dropdown).enabled || isBlocked(node)) {
                dropdown = null
            }
        }
    }

    /** Positions all widgets for the current screen size and rebuilds the children list (tab order = tree order). */
    private fun layout() {
        val (x, y) = TreeLayout.rootOrigin(root, width, height, fullscreen)
        originX = x
        originY = y
        val previousFocus = focused
        clearWidgets()
        for (node in nodes) {
            if (isBlocked(node)) continue
            widgetOf(node)?.let { addWidget(it) }
        }
        if (previousFocus != null && previousFocus !in children()) focused = null
        updateGeometry()
    }

    override fun init() = layout()

    override fun repositionElements() = layout()

    /** Recomputes the scrolled/clipped geometry and moves the vanilla widgets and custom contexts accordingly. */
    private fun updateGeometry() {
        val rootSize = if (fullscreen) (width - originX).coerceAtLeast(0) to (height - originY).coerceAtLeast(0) else null
        geometry = TreeLayout.geometry(placed, originX, originY, { id -> (scrollOffsets[id] ?: 0.0).toInt() }, rootSize)
        for (node in nodes) {
            val r = geometry[node.index].rect
            widgetOf(node)?.setRectangle(r.width, r.height, r.x0, r.y0)
            if (node.element is Custom) customs[node.element.id]?.place(r)
        }
    }

    private fun widgetOf(node: Node): AbstractWidget? = when (node.element) {
        is Button -> buttons[node.element.id]
        is TextField -> fields[node.element.id]
        is Checkbox -> checkboxes[node.element.id]
        is Slider -> sliders[node.element.id]
        is Dropdown -> dropdowns[node.element.id]
        else -> null
    }

    private fun isBlocked(node: Node): Boolean = TreeLayout.isBlocked(node.placed, topModalId)

    private fun isInteractive(p: PlacedElement): Boolean = when (val e = p.element) {
        is Button, is TextField, is Checkbox, is Slider, is Dropdown, is ItemSlot, is Custom -> true
        is Panel -> e.clickable
        else -> false
    }

    /** `true` if the mouse is over [node]'s visible area and the node may receive input right now. */
    private fun isHovered(node: Node, mouseX: Double, mouseY: Double): Boolean =
        dropdown == null && !isBlocked(node) && geometry[node.index].hit(mouseX, mouseY)

    // ---- rendering ----------------------------------------------------------------------------------------

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics) else super.extractBackground(graphics, mouseX, mouseY, a)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        updateGeometry()
        val mx = mouseX.toDouble()
        val my = mouseY.toDouble()
        var clip: Rect? = null
        fun applyClip(target: Rect?) {
            if (target == clip) return
            if (clip != null) graphics.disableScissor()
            if (target != null) graphics.enableScissor(target.x0, target.y0, target.x1, target.y1)
            clip = target
        }

        for (node in nodes) {
            val e = node.element
            if (e is Modal && node.placed.parentIndex == 0) {
                // every modal on its own layer above the tree; the topmost one dims everything below it
                applyClip(null)
                graphics.nextStratum()
                if (e.id == topModalId) graphics.fill(0, 0, width, height, e.dimColor.argb)
            }
            val g = geometry[node.index]
            applyClip(g.clip)
            val hovered = isHovered(node, mx, my)
            // widgets compute hover themselves: hide the mouse from blocked ones and while a dropdown list is open
            val wx = if (dropdown == null && !isBlocked(node)) mouseX else OFFSCREEN
            val wy = if (dropdown == null && !isBlocked(node)) mouseY else OFFSCREEN
            extractElement(graphics, node, g.rect, hovered, wx, wy, a)
            scrollbarsAfter[node.index]?.forEach { index ->
                applyClip(geometry[index].clip)
                extractScrollbar(graphics, nodes[index], mx, my)
            }
        }
        applyClip(null)

        dropdown?.let { open ->
            graphics.nextStratum()
            extractDropdownList(graphics, open, mx, my)
        }

        if (dropdown == null) extractTooltip(graphics, mx, my, mouseX, mouseY)
    }

    private fun extractElement(graphics: GuiGraphicsExtractor, node: Node, r: Rect, hovered: Boolean, mouseX: Int, mouseY: Int, a: Float) {
        val x = r.x0
        val y = r.y0
        val w = r.width
        val h = r.height
        when (val e = node.element) {
            is Panel -> {
                val background = when {
                    e.selected -> e.selectedBackground ?: e.background
                    hovered -> e.hoverBackground ?: e.background
                    else -> e.background
                }
                background?.let { graphics.fill(x, y, x + w, y + h, it.argb) }
                e.texture?.let { tex -> identifier(tex.toString())?.let { graphics.blit(RenderPipelines.GUI_TEXTURED, it, x, y, 0f, 0f, w, h, w, h) } }
                e.border?.let { graphics.outline(x, y, w, h, it.argb) }
                if (hovered && e.clickable) graphics.requestCursor(CursorTypes.POINTING_HAND)
            }
            is ScrollPanel -> {
                e.background?.let { graphics.fill(x, y, x + w, y + h, it.argb) }
                e.border?.let { graphics.outline(x, y, w, h, it.argb) }
            }
            is Modal -> {
                e.background?.let { graphics.fill(x, y, x + w, y + h, it.argb) }
                e.border?.let { graphics.outline(x, y, w, h, it.argb) }
            }
            is Text -> extractText(graphics, node, e, x, y, w)
            is Button -> buttons[e.id]?.extractRenderState(graphics, mouseX, mouseY, a)
            is TextField -> fields[e.id]?.extractRenderState(graphics, mouseX, mouseY, a)
            is Checkbox -> checkboxes[e.id]?.extractRenderState(graphics, mouseX, mouseY, a)
            is Slider -> sliders[e.id]?.extractRenderState(graphics, mouseX, mouseY, a)
            is Dropdown -> dropdowns[e.id]?.extractRenderState(graphics, mouseX, mouseY, a)
            is Image -> identifier(e.texture.toString())?.let {
                graphics.blit(RenderPipelines.GUI_TEXTURED, it, x, y, e.u, e.v, w, h, e.regionWidth, e.regionHeight, e.textureWidth, e.textureHeight)
            }
            is ItemDisplay -> {
                val item = items.get(e.id, e.item)
                val size = minOf(w, h)
                extractItem(graphics, item, x + (w - size) / 2, y + (h - size) / 2, size)
            }
            is ItemSlot -> extractSlot(graphics, e, r, hovered)
            is ProgressBar -> {
                graphics.fill(x, y, x + w, y + h, e.background.argb)
                val filled = (w * e.value.coerceIn(0f, 1f)).toInt()
                if (filled > 0) graphics.fill(x, y, x + filled, y + h, e.color.argb)
            }
            is Custom -> extractCustom(graphics, e, hovered, mouseX, mouseY)
        }
    }

    private fun extractText(graphics: GuiGraphicsExtractor, node: Node, e: Text, x: Int, y: Int, w: Int) {
        val component = node.text ?: return
        val lines = if (font.width(component) <= w || w <= 0) listOf(component.visualOrderText) else font.split(component, w)
        var lineY = y
        for (line in lines) {
            val lineWidth = font.width(line)
            val lineX = when (e.align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x + (w - lineWidth) / 2
                TextAlign.RIGHT -> x + w - lineWidth
            }
            graphics.text(font, line, lineX, lineY, e.color.argb, e.shadow)
            lineY += font.lineHeight
        }
    }

    /** Draws [item] scaled into a [size] x [size] square at ([x], [y]), with the (abbreviated) count. */
    private fun extractItem(graphics: GuiGraphicsExtractor, item: DisplayItem, x: Int, y: Int, size: Int) {
        if (size <= 0) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        if (size != 16) pose.scale(size / 16f, size / 16f)
        graphics.item(item.stack, 0, 0)
        graphics.itemDecorations(font, item.stack, 0, 0, item.countText)
        pose.popMatrix()
    }

    private fun extractSlot(graphics: GuiGraphicsExtractor, e: ItemSlot, r: Rect, hovered: Boolean) {
        val inset = if (e.slotBackground) 1 else 0
        val size = (minOf(r.width, r.height) - 2 * inset).coerceAtLeast(0)
        val itemX = r.x0 + (r.width - size) / 2
        val itemY = r.y0 + (r.height - size) / 2
        val highlight = hovered && e.highlight && e.enabled
        // vanilla highlight sprites are 24x24 around a 16x16 item
        val hlSize = size * 24 / 16
        val hlX = itemX - (hlSize - size) / 2
        val hlY = itemY - (hlSize - size) / 2

        if (e.slotBackground) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, r.x0, r.y0, r.width, r.height)
        if (highlight) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, hlX, hlY, hlSize, hlSize)
        val data = e.item
        if (data != null) {
            extractItem(graphics, items.get(e.id, data), itemX, itemY, size)
        } else {
            e.placeholderSprite?.let { sprite ->
                identifier(sprite.toString())?.let { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, it, itemX, itemY, size, size) }
            }
        }
        if (highlight) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, hlX, hlY, hlSize, hlSize)
        if (!e.enabled) graphics.fill(r.x0, r.y0, r.x1, r.y1, DISABLED_SLOT_OVERLAY)
        if (e.selected) {
            graphics.outline(r.x0 - 1, r.y0 - 1, r.width + 2, r.height + 2, SELECTED_FRAME)
            graphics.outline(r.x0, r.y0, r.width, r.height, SELECTED_FRAME_INNER)
        }
        if (hovered) graphics.requestCursor(if (e.enabled) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
    }

    private fun extractScrollbar(graphics: GuiGraphicsExtractor, node: Node, mouseX: Double, mouseY: Double) {
        val e = node.element as ScrollPanel
        if (!e.scrollbar || !ScrollMath.canScroll(e.contentHeight, e.bounds.height)) return
        val bar = scrollbarRect(node)
        val offset = scrollOffsets[e.id] ?: 0.0
        val thumbH = ScrollMath.thumbHeight(e.contentHeight, bar.height)
        val thumbY = bar.y0 + ScrollMath.thumbY(offset, e.contentHeight, bar.height)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, bar.x0, bar.y0, bar.width, bar.height)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, bar.x0, thumbY, bar.width, thumbH)
        if (scrollbarDrag?.panelId == e.id) {
            graphics.requestCursor(CursorTypes.RESIZE_NS)
        } else if (isHovered(node, mouseX, mouseY) && bar.contains(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND)
        }
    }

    /** Scrollbar of a scroll panel: right edge inside its bounds. */
    private fun scrollbarRect(node: Node): Rect {
        val r = geometry[node.index].rect
        return Rect(r.x1 - ScrollMath.SCROLLBAR_WIDTH, r.y0, r.x1, r.y1)
    }

    private fun extractCustom(graphics: GuiGraphicsExtractor, e: Custom, hovered: Boolean, mouseX: Int, mouseY: Int) {
        val ctx = customs[e.id] ?: return
        val widget = ctx.widget()
        if (widget == null) {
            extractPlaceholder(graphics, ctx.x, ctx.y, ctx.width, ctx.height, ctx.type.toString())
            return
        }
        graphics.enableScissor(ctx.x, ctx.y, ctx.x + ctx.width, ctx.y + ctx.height)
        try {
            val target = GuiDrawTarget(graphics, font, host.textures, mouseX, mouseY)
            widget.render(target, ctx.x, ctx.y, ctx.width, ctx.height, ctx.properties, hovered, mouseX.toDouble(), mouseY.toDouble())
        } finally {
            graphics.disableScissor()
        }
    }

    private fun extractPlaceholder(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, label: String) {
        graphics.fill(x, y, x + w, y + h, 0x80400040.toInt())
        graphics.outline(x, y, w, h, 0xFFFF00FF.toInt())
        val text = font.plainSubstrByWidth(label, (w - 4).coerceAtLeast(0))
        if (h >= font.lineHeight + 2) graphics.text(font, text, x + 2, y + (h - font.lineHeight) / 2 + 1, 0xFFFFFFFF.toInt(), true)
    }

    /**
     * Tooltip of the topmost element under the mouse that has one (its own tooltip and/or the vanilla item tooltip).
     * An interactive element without a tooltip hides the tooltips of elements below it.
     */
    private fun extractTooltip(graphics: GuiGraphicsExtractor, mx: Double, my: Double, mouseX: Int, mouseY: Int) {
        for (node in nodes.asReversed()) {
            if (!isHovered(node, mx, my)) continue
            val extra = node.tooltipLines()
            val item = itemForTooltip(node)
            if (item != null) {
                val lines = ArrayList<FormattedCharSequence>()
                val (stack, failed) = item.first.stack to item.first.failed
                if (failed) {
                    lines += Component.literal(item.second.itemId.toString()).withStyle(ChatFormatting.RED).visualOrderText
                } else {
                    Screen.getTooltipFromItem(minecraft, stack).mapTo(lines) { it.visualOrderText }
                }
                if (item.first.count > 999) {
                    lines += Component.literal("x" + String.format(Locale.ROOT, "%,d", item.first.count)).withStyle(ChatFormatting.GRAY).visualOrderText
                }
                lines += extra
                graphics.setTooltipForNextFrame(
                    font, lines, if (failed) java.util.Optional.empty() else stack.tooltipImage, DefaultTooltipPositioner.INSTANCE,
                    mouseX, mouseY, false, if (failed) null else stack.get(DataComponents.TOOLTIP_STYLE),
                )
                return
            }
            if (extra.isNotEmpty()) {
                graphics.setTooltipForNextFrame(font, extra, mouseX, mouseY)
                return
            }
            if (isInteractive(node.placed)) return
        }
    }

    private fun itemForTooltip(node: Node): Pair<DisplayItem, ItemStackData>? = when (val e = node.element) {
        is ItemDisplay -> if (e.showItemTooltip) items.get(e.id, e.item) to e.item else null
        is ItemSlot -> e.item?.takeIf { e.showItemTooltip }?.let { items.get(e.id, it) to it }
        else -> null
    }

    // ---- dropdown overlay ---------------------------------------------------------------------------------

    private class OpenDropdown(val head: DropdownHead, val state: DropdownState) {
        var list: Rect = Rect(0, 0, 0, 0)
        var drag: Pair<Double, Double>? = null // mouse y and scroll offset (px) at drag start
    }

    private fun toggleDropdown(head: DropdownHead) {
        if (dropdown?.head === head) {
            dropdown = null
            return
        }
        if (!head.active || head.options.isEmpty()) return
        val open = OpenDropdown(head, DropdownState(head.options.size, MAX_DROPDOWN_ROWS, head.selectedIndex))
        dropdown = open
        layoutDropdown(open)
        setFocused(head)
    }

    private fun closeDropdown() {
        dropdown = null
    }

    /** Places the list below the head (above if there is more room there) and fits the visible row count. */
    private fun layoutDropdown(open: OpenDropdown) {
        val head = open.head
        val count = head.options.size
        val labelWidth = head.labels.maxOfOrNull(font::width) ?: 0
        val scrollbarSpace = if (count > MAX_DROPDOWN_ROWS) ScrollMath.SCROLLBAR_WIDTH + 1 else 0
        val listWidth = maxOf(head.width, labelWidth + 12 + scrollbarSpace).coerceAtMost((width - 4).coerceAtLeast(1))
        val below = height - (head.y + head.height) - 2
        val above = head.y - 2
        val wanted = minOf(count, MAX_DROPDOWN_ROWS) * DROPDOWN_ROW + 2
        val placeAbove = wanted > below && above > below
        val space = if (placeAbove) above else below
        val rows = minOf(count, MAX_DROPDOWN_ROWS, ((space - 2) / DROPDOWN_ROW).coerceAtLeast(1))
        val listHeight = rows * DROPDOWN_ROW + 2
        val x = head.x.coerceAtMost(width - listWidth - 2).coerceAtLeast(2)
        val y = if (placeAbove) head.y - listHeight else head.y + head.height
        open.list = Rect.of(x, y, listWidth, listHeight)
        if (open.state.optionCount != count || open.state.visibleRows != rows) open.state.resize(count, rows)
    }

    private fun dropdownScrollbar(open: OpenDropdown): Rect? {
        if (!open.state.isScrollable) return null
        val l = open.list
        return Rect(l.x1 - 1 - ScrollMath.SCROLLBAR_WIDTH, l.y0 + 1, l.x1 - 1, l.y1 - 1)
    }

    private fun dropdownRowAt(open: OpenDropdown, mx: Double, my: Double): Int {
        val l = open.list
        if (!l.contains(mx, my) || dropdownScrollbar(open)?.contains(mx, my) == true) return -1
        val row = ((my - l.y0 - 1) / DROPDOWN_ROW).toInt()
        return open.state.optionAtRow(row)
    }

    private fun extractDropdownList(graphics: GuiGraphicsExtractor, open: OpenDropdown, mx: Double, my: Double) {
        layoutDropdown(open)
        val l = open.list
        val state = open.state
        val head = open.head
        graphics.fill(l.x0, l.y0, l.x1, l.y1, DROPDOWN_BACKGROUND)
        graphics.outline(l.x0, l.y0, l.width, l.height, DROPDOWN_BORDER)
        val bar = dropdownScrollbar(open)
        val textRight = (bar?.x0 ?: (l.x1 - 1)) - 2
        graphics.enableScissor(l.x0 + 1, l.y0 + 1, textRight, l.y1 - 1)
        val selected = head.selectedIndex
        for (row in 0 until state.visibleRows) {
            val index = state.optionAtRow(row)
            if (index < 0) break
            val rowY = l.y0 + 1 + row * DROPDOWN_ROW
            if (index == state.highlighted) graphics.fill(l.x0 + 1, rowY, textRight + 2, rowY + DROPDOWN_ROW, DROPDOWN_HIGHLIGHT)
            val label = head.labels.getOrNull(index) ?: Component.literal(head.options[index].value)
            val color = if (index == selected) DROPDOWN_SELECTED_TEXT else -1
            graphics.text(font, label, l.x0 + 5, rowY + (DROPDOWN_ROW - font.lineHeight) / 2 + 1, color, true)
        }
        graphics.disableScissor()
        if (bar != null) {
            val content = state.optionCount * DROPDOWN_ROW
            val viewport = bar.height
            val thumbH = ScrollMath.thumbHeight(content, viewport)
            val thumbY = bar.y0 + ScrollMath.thumbY(state.scrollTop * DROPDOWN_ROW.toDouble(), content, viewport)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, bar.x0, bar.y0, bar.width, bar.height)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, bar.x0, thumbY, bar.width, thumbH)
        }
        if (l.contains(mx, my)) graphics.requestCursor(CursorTypes.POINTING_HAND)
    }

    private fun dropdownMouseClicked(open: OpenDropdown, event: MouseButtonEvent): Boolean {
        val mx = event.x()
        val my = event.y()
        val bar = dropdownScrollbar(open)
        if (bar != null && bar.contains(mx, my)) {
            val content = open.state.optionCount * DROPDOWN_ROW
            val thumbY = bar.y0 + ScrollMath.thumbY(open.state.scrollTop * DROPDOWN_ROW.toDouble(), content, bar.height)
            val thumbH = ScrollMath.thumbHeight(content, bar.height)
            if (my < thumbY || my >= thumbY + thumbH) {
                val offset = ScrollMath.offsetForTrackClick(my - bar.y0, content, bar.height)
                open.state.scrollTo(Math.round(offset / DROPDOWN_ROW).toInt())
            }
            open.drag = my to open.state.scrollTop * DROPDOWN_ROW.toDouble()
            return true
        }
        val index = dropdownRowAt(open, mx, my)
        if (index >= 0) {
            playClickSound()
            open.head.choose(index)
            closeDropdown()
            return true
        }
        if (!open.list.contains(mx, my)) closeDropdown() // click outside (also on the head itself) closes the list
        return true
    }

    private fun dropdownKeyPressed(open: OpenDropdown, event: KeyEvent): Boolean {
        val state = open.state
        when {
            event.isEscape -> closeDropdown()
            event.isUp -> state.move(-1)
            event.isDown -> state.move(1)
            event.key() == InputConstants.KEY_PAGEUP -> state.pageUp()
            event.key() == InputConstants.KEY_PAGEDOWN -> state.pageDown()
            event.key() == InputConstants.KEY_HOME -> state.first()
            event.key() == InputConstants.KEY_END -> state.last()
            event.isConfirmation || event.isSelection -> {
                if (state.highlighted >= 0) open.head.choose(state.highlighted)
                closeDropdown()
            }
            event.isCycleFocus -> closeDropdown()
        }
        return true
    }

    // ---- input --------------------------------------------------------------------------------------------

    private class ScrollbarDrag(val panelId: String, val startMouseY: Double, val startOffset: Double)

    override fun mouseMoved(x: Double, y: Double) {
        val open = dropdown ?: return super.mouseMoved(x, y)
        val index = dropdownRowAt(open, x, y)
        if (index >= 0 && index != open.state.highlighted) open.state.highlight(index)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        updateGeometry()
        dropdown?.let { return dropdownMouseClicked(it, event) }
        val mx = event.x()
        val my = event.y()
        val protocolButton = MouseButtons.toProtocol(event.button())

        // scrollbars (drawn above the panel's children) first, innermost panel first
        if (event.button() == LEFT) {
            for (node in nodes.asReversed()) {
                val e = node.element as? ScrollPanel ?: continue
                if (!e.scrollbar || !ScrollMath.canScroll(e.contentHeight, e.bounds.height) || !isHovered(node, mx, my)) continue
                val bar = scrollbarRect(node)
                if (!bar.contains(mx, my)) continue
                var offset = scrollOffsets[e.id] ?: 0.0
                val thumbY = bar.y0 + ScrollMath.thumbY(offset, e.contentHeight, bar.height)
                val thumbH = ScrollMath.thumbHeight(e.contentHeight, bar.height)
                if (my < thumbY || my >= thumbY + thumbH) {
                    offset = ScrollMath.offsetForTrackClick(my - bar.y0, e.contentHeight, bar.height)
                    setScroll(e, offset)
                }
                scrollbarDrag = ScrollbarDrag(e.id, my, offset)
                return true
            }
        }

        val target = TreeLayout.topmostAt(placed, geometry, mx, my, topModalId, ::isInteractive)
        if (target < 0) {
            setFocused(null)
            if (topModalId != null) return true // input outside the modal is blocked
            if (!hasTextFieldFocus()) ServerKeybinds.onScreenMouse(event, true)
            return false
        }
        val node = nodes[target]
        val widget = widgetOf(node)
        if (widget != null) {
            if (widget.mouseClicked(event, doubleClick)) {
                if (widget.shouldTakeFocusAfterInteraction()) setFocused(widget)
                if (event.button() == LEFT) isDragging = true
            } else if (focused !== widget) {
                setFocused(null)
            }
            return true
        }
        setFocused(null)
        when (val e = node.element) {
            is ItemSlot -> if (e.enabled && protocolButton >= 0) {
                host.send(ScreenClick(screenId, e.id, protocolButton, Modifiers.of(event)))
            }
            is Panel -> if (protocolButton >= 0) {
                playClickSound()
                host.send(ScreenClick(screenId, e.id, protocolButton, Modifiers.of(event)))
            }
            is Custom -> {
                val ctx = customs[e.id] ?: return true
                val widget = ctx.widget()
                val report = if (widget == null) true else {
                    pressedCustom = ctx to event.button()
                    widget.mouseClicked(ctx.width, ctx.height, ctx.properties, mx - ctx.x, my - ctx.y, protocolButton)
                }
                if (report && protocolButton >= 0) host.send(ScreenClick(screenId, ctx.elementId, protocolButton, Modifiers.of(event)))
            }
            else -> {}
        }
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        pressedCustom?.let { (ctx, button) ->
            if (button == event.button()) {
                pressedCustom = null
                updateGeometry()
                if (customs[ctx.elementId] === ctx) {
                    ctx.widget()?.mouseReleased(ctx.width, ctx.height, ctx.properties, event.x() - ctx.x, event.y() - ctx.y, MouseButtons.toProtocol(button))
                }
            }
        }
        dropdown?.let { open ->
            if (open.drag != null) {
                open.drag = null
                return true
            }
        }
        if (scrollbarDrag != null) {
            scrollbarDrag = null
            return true
        }
        ServerKeybinds.onScreenMouse(event, false)
        return super.mouseReleased(event)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        updateGeometry()
        dropdown?.let { open ->
            val drag = open.drag ?: return true
            val bar = dropdownScrollbar(open) ?: return true
            val offset = ScrollMath.dragOffset(drag.second, event.y() - drag.first, open.state.optionCount * DROPDOWN_ROW, bar.height)
            open.state.scrollTo(Math.round(offset / DROPDOWN_ROW).toInt())
            return true
        }
        scrollbarDrag?.let { drag ->
            val node = nodes.firstOrNull { it.element.id == drag.panelId } ?: return true
            val e = node.element as ScrollPanel
            setScroll(e, ScrollMath.dragOffset(drag.startOffset, event.y() - drag.startMouseY, e.contentHeight, e.bounds.height))
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        updateGeometry()
        dropdown?.let { open ->
            if (open.list.contains(x, y) && scrollY != 0.0) open.state.scrollBy(if (scrollY > 0) -1 else 1)
            return true
        }
        if (scrollY == 0.0) return false
        // a Lua widget under the mouse may consume the wheel
        val customIndex = TreeLayout.topmostAt(placed, geometry, x, y, topModalId) { it.element is Custom }
        if (customIndex >= 0) {
            val ctx = customs[nodes[customIndex].element.id]
            val widget = ctx?.widget()
            if (ctx != null && widget != null && widget.mouseScrolled(ctx.width, ctx.height, ctx.properties, x - ctx.x, y - ctx.y, scrollY)) return true
        }
        // innermost scroll panel under the mouse that can still move in that direction
        for (node in nodes.asReversed()) {
            val e = node.element as? ScrollPanel ?: continue
            if (!isHovered(node, x, y)) continue
            val current = scrollOffsets[e.id] ?: 0.0
            val next = ScrollMath.clamp(current - scrollY * ScrollMath.WHEEL_STEP, e.contentHeight, e.bounds.height)
            if (next != current) {
                setScroll(e, next)
                return true
            }
        }
        return topModalId != null
    }

    private fun setScroll(panel: ScrollPanel, offset: Double) {
        scrollOffsets[panel.id] = ScrollMath.clamp(offset, panel.contentHeight, panel.bounds.height)
        updateGeometry()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        dropdown?.let { return dropdownKeyPressed(it, event) }
        val focusedField = focused as? ServerTextField
        if (focusedField != null && focusedField.canConsumeInput() && event.isConfirmation) {
            focusedField.flush(force = true)
            return true
        }
        if (event.isEscape) {
            topModalId?.let { modal ->
                host.send(ModalDismiss(screenId, modal))
                return true
            }
        }
        if (!hasTextFieldFocus() && !event.isEscape) ServerKeybinds.onScreenKey(event, true)
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        ServerKeybinds.onScreenKey(event, false)
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (dropdown != null) return true
        return super.charTyped(event)
    }

    /** Keeps the keyboard-focused widget visible inside its scroll panel. */
    override fun setFocused(listener: GuiEventListener?) {
        super.setFocused(listener)
        if (listener == null || !minecraft.lastInputType.isKeyboard) return
        val node = nodes.firstOrNull { widgetOf(it) === listener } ?: return
        val sp = node.placed.scrollParentIndex
        if (sp < 0) return
        val panel = placed[sp]
        val e = panel.element as ScrollPanel
        val top = node.placed.offsetY - panel.offsetY
        val current = scrollOffsets[e.id] ?: 0.0
        setScroll(e, ScrollMath.ensureVisible(current, top, top + node.placed.height, e.contentHeight, e.bounds.height))
    }

    private fun hasTextFieldFocus(): Boolean = (focused as? EditBox)?.canConsumeInput() == true

    private fun playClickSound() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    override fun tick() {
        fields.values.forEach { it.flush(force = false) }
        sliders.values.forEach { it.flush(force = false) }
    }

    override fun removed() {
        if (closeSilently) return
        fields.values.forEach { it.flush(force = true) }
        sliders.values.forEach { it.flush(force = true) }
        host.send(ScreenClosed(screenId))
    }

    override fun isPauseScreen(): Boolean = false

    // ---- nodes --------------------------------------------------------------------------------------------

    /** A placed element plus data derived once per tree (parsed components). */
    private inner class Node(val index: Int, val placed: PlacedElement) {
        val element: Element get() = placed.element

        val text: Component? = when (val e = placed.element) {
            is Text -> Components.parse(e.text)
            is Button -> Components.parse(e.label)
            is Checkbox -> Components.parse(e.label)
            is Slider -> Components.parse(e.label)
            else -> null
        }
        val placeholder: Component? = when (val e = placed.element) {
            is TextField -> Components.parse(e.placeholder)
            is Dropdown -> Components.parse(e.placeholder)
            else -> null
        }
        val optionLabels: List<Component> = (placed.element as? Dropdown)?.options
            ?.map { Components.parse(it.label) ?: Component.literal(it.value) } ?: emptyList()
        private val tooltip: Component? = Components.parse(placed.element.tooltip)
        private var tooltipLines: List<FormattedCharSequence>? = null

        /** The element's own tooltip, wrapped (and split at `\n`). */
        fun tooltipLines(): List<FormattedCharSequence> {
            val component = tooltip ?: return emptyList()
            return tooltipLines ?: font.split(component, TOOLTIP_WIDTH).also { tooltipLines = it }
        }
    }

    private fun identifier(id: String): Identifier? = Identifier.tryParse(id)

    /** A custom element: its bounds, properties (updated in place) and Lua widget instance (kept across updates). */
    private inner class CustomContext(val elementId: String) {
        var type: Identifier = Identifier.fromNamespaceAndPath("kiln", "unknown")
        var x = 0
        var y = 0
        var width = 0
        var height = 0
        private val props = LinkedHashMap<String, String>()
        val properties: Map<String, String> = java.util.Collections.unmodifiableMap(props)
        private var instance: LuaExtension.WidgetInstance? = null
        private var created = false

        fun apply(e: Custom) {
            val newType = Identifier.tryParse(e.type.toString()) ?: type
            if (newType != type) {
                instance = null
                created = false
            }
            type = newType
            props.clear()
            props.putAll(e.properties)
        }

        /** The Lua widget (created on first use), or `null` for a placeholder (unknown type, extension unloaded). */
        fun widget(): LuaExtension.WidgetInstance? {
            if (!created) {
                created = true
                instance = host.createWidget(type)
            }
            return instance?.takeIf { it.extension.alive }
        }

        fun place(r: Rect) {
            x = r.x0
            y = r.y0
            width = r.width
            height = r.height
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("kiln")

        /** Mouse position handed to widgets that must not react to the mouse (blocked / under an open list). */
        const val OFFSCREEN = -100_000

        /** Left mouse button in 26.3 (SDL) numbering. */
        const val LEFT = 1

        const val TOOLTIP_WIDTH = 200
        const val MAX_DROPDOWN_ROWS = 10
        const val DROPDOWN_ROW = 12
        const val DROPDOWN_BACKGROUND = 0xF0101010.toInt()
        const val DROPDOWN_BORDER = 0xFFA0A0A0.toInt()
        const val DROPDOWN_HIGHLIGHT = 0x50FFFFFF
        const val DROPDOWN_SELECTED_TEXT = 0xFFFFFF55.toInt()
        const val DISABLED_SLOT_OVERLAY = 0x90202020.toInt()
        const val SELECTED_FRAME = 0xFFFFFFFF.toInt()
        const val SELECTED_FRAME_INNER = 0x80FFFFFF.toInt()

        val SLOT_SPRITE: Identifier = Identifier.withDefaultNamespace("container/slot")
        val SLOT_HIGHLIGHT_BACK: Identifier = Identifier.withDefaultNamespace("container/slot_highlight_back")
        val SLOT_HIGHLIGHT_FRONT: Identifier = Identifier.withDefaultNamespace("container/slot_highlight_front")
        val SCROLLER: Identifier = Identifier.withDefaultNamespace("widget/scroller")
        val SCROLLER_BACKGROUND: Identifier = Identifier.withDefaultNamespace("widget/scroller_background")
    }
}
