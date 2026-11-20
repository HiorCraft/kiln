package dev.kiln.client.screen

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.ext.GuiDrawTarget
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.screen.widget.DropdownHead
import dev.kiln.client.screen.widget.DropdownOverlay
import dev.kiln.client.screen.widget.Modifiers
import dev.kiln.client.screen.widget.ServerButton
import dev.kiln.client.screen.widget.ServerCheckbox
import dev.kiln.client.screen.widget.ServerSlider
import dev.kiln.client.screen.widget.ServerTextField
import dev.kiln.client.screen.widget.WidgetEnv
import dev.kiln.client.screen.widget.flush
import dev.kiln.client.screen.widget.playClickSound
import dev.kiln.protocol.element.Button
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.Color
import dev.kiln.protocol.element.Custom
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Image
import dev.kiln.protocol.element.ItemDisplay
import dev.kiln.protocol.element.ItemSlot
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.message.ModalDismiss
import dev.kiln.protocol.element.Panel
import dev.kiln.protocol.element.ProgressBar
import dev.kiln.protocol.message.ScreenClick
import dev.kiln.protocol.message.ScreenClosed
import dev.kiln.protocol.element.ScrollPanel
import dev.kiln.protocol.message.ServerboundMessage
import dev.kiln.protocol.element.Slider
import dev.kiln.protocol.element.Text
import dev.kiln.protocol.TextAlign
import dev.kiln.protocol.element.TextField
import dev.kiln.protocol.element.topModal
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
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import java.util.Locale
import java.util.Optional

/**
 * Renders one server-described GUI tree (centered, or placed at its own x/y for fullscreen screens).
 *
 * - Elements are drawn in [TreeLayout.flatten] order; modals (and their dimming) after the rest of the tree, an open
 *   dropdown list ([DropdownOverlay]) after everything.
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
    private var root: Element,
    private val host: ScreenHost,
    /** Root placed at its own x/y and stretched to the window (protocol v2 `fullscreen`). */
    private var fullscreen: Boolean = false,
) : Screen(Component.empty()) {

    private var nodes: List<ScreenNode> = emptyList()
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

    private var dropdown: DropdownOverlay? = null

    /** Custom element that received a mouse press (gets the matching release) and the button. */
    private var pressedCustom: Pair<CustomElement, Int>? = null

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

    private val buttons = KeyedPool<ScreenNode, ServerButton>(
        idOf = { it.element.id },
        create = { ServerButton(env, it.element.id) },
        update = { button, node -> button.apply(node.element as Button, node.text) },
    )

    private val fields = KeyedPool<ScreenNode, ServerTextField>(
        idOf = { it.element.id },
        create = { node -> ServerTextField(env, node.element.id, (node.element as TextField).value) },
        update = { field, node -> field.apply(node.element as TextField, node.placeholder) },
    )

    private val checkboxes = KeyedPool<ScreenNode, ServerCheckbox>(
        idOf = { it.element.id },
        create = { ServerCheckbox(env, it.element.id, it.element as Checkbox) },
        update = { box, node -> box.apply(node.element as Checkbox, node.text) },
    )

    private val sliders = KeyedPool<ScreenNode, ServerSlider>(
        idOf = { it.element.id },
        create = { ServerSlider(env, it.element.id, it.element as Slider) },
        update = { slider, node -> slider.apply(node.element as Slider, node.text) },
    )

    private val dropdowns = KeyedPool<ScreenNode, DropdownHead>(
        idOf = { it.element.id },
        create = { DropdownHead(env, it.element.id, it.element as Dropdown) },
        update = { head, node -> head.apply(node.element as Dropdown, node.optionLabels, node.placeholder) },
    )

    private val customs = KeyedPool<ScreenNode, CustomElement>(
        idOf = { it.element.id },
        create = { CustomElement(it.element.id, host::createWidget) },
        update = { custom, node -> custom.apply(node.element as Custom) },
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
        nodes = placed.mapIndexed(::ScreenNode)
        topModalId = newRoot.topModal()?.id
        syncPools()
        syncScrollState()
        if (dropdown?.let(::isStillUsable) == false) closeDropdown()
    }

    private fun syncPools() {
        buttons.sync(nodes.filter { it.element is Button })
        fields.sync(nodes.filter { it.element is TextField })
        checkboxes.sync(nodes.filter { it.element is Checkbox })
        sliders.sync(nodes.filter { it.element is Slider })
        dropdowns.sync(nodes.filter { it.element is Dropdown })
        customs.sync(nodes.filter { it.element is Custom })
        items.retain(nodes.filter { it.element is ItemDisplay || it.element is ItemSlot }.map { it.element.id }.toSet())
    }

    /** Keeps the scroll offsets per id (clamped to the new content height) and a running scrollbar drag. */
    private fun syncScrollState() {
        val panels = nodes.filter { it.element is ScrollPanel }
        scrollOffsets.keys.retainAll(panels.map { it.element.id }.toSet())
        for (node in panels) {
            val panel = node.element as ScrollPanel
            scrollOffsets[panel.id]?.let { scrollOffsets[panel.id] = ScrollMath.clamp(it, panel.contentHeight, panel.bounds.height) }
        }
        scrollbarsAfter = panels.groupBy({ it.placed.subtreeEnd }, { it.index }).mapValues { (_, list) -> list.sortedDescending() }
        scrollbarDrag?.let { drag -> if (panels.none { it.element.id == drag.panelId }) scrollbarDrag = null }
    }

    /** An open dropdown stays open if it still exists, is enabled and not blocked by a (new) modal. */
    private fun isStillUsable(open: DropdownOverlay): Boolean {
        val id = open.head.elementId
        val node = nodes.firstOrNull { it.element.id == id && it.element is Dropdown } ?: return false
        return dropdowns[id] === open.head && (node.element as Dropdown).enabled && !isBlocked(node)
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

    /** Recomputes the scrolled/clipped geometry and moves the vanilla widgets and custom elements accordingly. */
    private fun updateGeometry() {
        val rootSize = if (fullscreen) (width - originX).coerceAtLeast(0) to (height - originY).coerceAtLeast(0) else null
        geometry = TreeLayout.geometry(placed, originX, originY, { id -> scrollOffset(id).toInt() }, rootSize)
        for (node in nodes) {
            val r = geometry[node.index].rect
            widgetOf(node)?.setRectangle(r.width, r.height, r.x0, r.y0)
            if (node.element is Custom) customs[node.element.id]?.place(r)
        }
    }

    private fun widgetOf(node: ScreenNode): AbstractWidget? = when (node.element) {
        is Button -> buttons[node.element.id]
        is TextField -> fields[node.element.id]
        is Checkbox -> checkboxes[node.element.id]
        is Slider -> sliders[node.element.id]
        is Dropdown -> dropdowns[node.element.id]
        else -> null
    }

    private fun isBlocked(node: ScreenNode): Boolean = TreeLayout.isBlocked(node.placed, topModalId)

    private fun isInteractive(p: PlacedElement): Boolean = when (val e = p.element) {
        is Button, is TextField, is Checkbox, is Slider, is Dropdown, is ItemSlot, is Custom -> true
        is Panel -> e.clickable
        else -> false
    }

    /** `true` if [node] may receive mouse input right now (not blocked by a modal or an open dropdown list). */
    private fun receivesMouse(node: ScreenNode): Boolean = dropdown == null && !isBlocked(node)

    /** `true` if the mouse is over [node]'s visible area and the node may receive input right now. */
    private fun isHovered(node: ScreenNode, mouseX: Double, mouseY: Double): Boolean =
        receivesMouse(node) && geometry[node.index].hit(mouseX, mouseY)

    // ---- scrolling ----------------------------------------------------------------------------------------

    private fun scrollOffset(panelId: String): Double = scrollOffsets[panelId] ?: 0.0

    private fun setScroll(panel: ScrollPanel, offset: Double) {
        scrollOffsets[panel.id] = ScrollMath.clamp(offset, panel.contentHeight, panel.bounds.height)
        updateGeometry()
    }

    private fun hasScrollbar(panel: ScrollPanel): Boolean =
        panel.scrollbar && ScrollMath.canScroll(panel.contentHeight, panel.bounds.height)

    /** Scrollbar of a scroll panel: right edge inside its bounds. */
    private fun scrollbarRect(node: ScreenNode): Rect {
        val r = geometry[node.index].rect
        return Rect(r.x1 - ScrollMath.SCROLLBAR_WIDTH, r.y0, r.x1, r.y1)
    }

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
                applyClip(null)
                extractModalLayer(graphics, e)
            }
            val g = geometry[node.index]
            applyClip(g.clip)
            // widgets compute hover themselves: hide the mouse from those that must not react to it
            val receivesMouse = receivesMouse(node)
            val widgetMouseX = if (receivesMouse) mouseX else OFFSCREEN
            val widgetMouseY = if (receivesMouse) mouseY else OFFSCREEN
            extractElement(graphics, node, g.rect, isHovered(node, mx, my), widgetMouseX, widgetMouseY, a)
            scrollbarsAfter[node.index]?.forEach { index ->
                applyClip(geometry[index].clip)
                extractScrollbar(graphics, nodes[index], mx, my)
            }
        }
        applyClip(null)

        val open = dropdown
        if (open != null) {
            graphics.nextStratum()
            open.layout(width, height)
            open.extract(graphics, mx, my)
        } else {
            extractTooltip(graphics, mx, my, mouseX, mouseY)
        }
    }

    /** Every modal is drawn on its own layer above the tree; the topmost one dims everything below it. */
    private fun extractModalLayer(graphics: GuiGraphicsExtractor, modal: Modal) {
        graphics.nextStratum()
        if (modal.id == topModalId) graphics.fill(0, 0, width, height, modal.dimColor.argb)
    }

    private fun extractElement(graphics: GuiGraphicsExtractor, node: ScreenNode, r: Rect, hovered: Boolean, mouseX: Int, mouseY: Int, a: Float) {
        when (val e = node.element) {
            is Panel -> extractPanel(graphics, e, r, hovered)
            is ScrollPanel -> extractBox(graphics, r, e.background, e.border)
            is Modal -> extractBox(graphics, r, e.background, e.border)
            is Text -> extractText(graphics, node, e, r)
            is Button, is TextField, is Checkbox, is Slider, is Dropdown -> widgetOf(node)?.extractRenderState(graphics, mouseX, mouseY, a)
            is Image -> identifier(e.texture.toString())?.let {
                graphics.blit(RenderPipelines.GUI_TEXTURED, it, r.x0, r.y0, e.u, e.v, r.width, r.height, e.regionWidth, e.regionHeight, e.textureWidth, e.textureHeight)
            }
            is ItemDisplay -> {
                val size = minOf(r.width, r.height)
                extractItem(graphics, items.get(e.id, e.item), r.x0 + (r.width - size) / 2, r.y0 + (r.height - size) / 2, size)
            }
            is ItemSlot -> extractSlot(graphics, e, r, hovered)
            is ProgressBar -> extractProgressBar(graphics, e, r)
            is Custom -> extractCustom(graphics, e, hovered, mouseX, mouseY)
        }
    }

    private fun extractBox(graphics: GuiGraphicsExtractor, r: Rect, background: Color?, border: Color?) {
        background?.let { graphics.fill(r.x0, r.y0, r.x1, r.y1, it.argb) }
        border?.let { graphics.outline(r.x0, r.y0, r.width, r.height, it.argb) }
    }

    private fun extractPanel(graphics: GuiGraphicsExtractor, e: Panel, r: Rect, hovered: Boolean) {
        val background = when {
            e.selected -> e.selectedBackground ?: e.background
            hovered -> e.hoverBackground ?: e.background
            else -> e.background
        }
        background?.let { graphics.fill(r.x0, r.y0, r.x1, r.y1, it.argb) }
        e.texture?.let { texture ->
            identifier(texture.toString())?.let { graphics.blit(RenderPipelines.GUI_TEXTURED, it, r.x0, r.y0, 0f, 0f, r.width, r.height, r.width, r.height) }
        }
        e.border?.let { graphics.outline(r.x0, r.y0, r.width, r.height, it.argb) }
        if (hovered && e.clickable) graphics.requestCursor(CursorTypes.POINTING_HAND)
    }

    private fun extractProgressBar(graphics: GuiGraphicsExtractor, e: ProgressBar, r: Rect) {
        graphics.fill(r.x0, r.y0, r.x1, r.y1, e.background.argb)
        val filled = (r.width * e.value.coerceIn(0f, 1f)).toInt()
        if (filled > 0) graphics.fill(r.x0, r.y0, r.x0 + filled, r.y1, e.color.argb)
    }

    private fun extractText(graphics: GuiGraphicsExtractor, node: ScreenNode, e: Text, r: Rect) {
        val component = node.text ?: return
        val w = r.width
        val lines = if (font.width(component) <= w || w <= 0) listOf(component.visualOrderText) else font.split(component, w)
        var lineY = r.y0
        for (line in lines) {
            val lineWidth = font.width(line)
            val lineX = when (e.align) {
                TextAlign.LEFT -> r.x0
                TextAlign.CENTER -> r.x0 + (w - lineWidth) / 2
                TextAlign.RIGHT -> r.x0 + w - lineWidth
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
        if (size != ITEM_SIZE) pose.scale(size / ITEM_SIZE.toFloat(), size / ITEM_SIZE.toFloat())
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
        val highlightSize = size * SLOT_HIGHLIGHT_SIZE / ITEM_SIZE
        val highlightX = itemX - (highlightSize - size) / 2
        val highlightY = itemY - (highlightSize - size) / 2

        if (e.slotBackground) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, r.x0, r.y0, r.width, r.height)
        if (highlight) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, highlightX, highlightY, highlightSize, highlightSize)
        val data = e.item
        if (data != null) {
            extractItem(graphics, items.get(e.id, data), itemX, itemY, size)
        } else {
            e.placeholderSprite?.let { sprite ->
                identifier(sprite.toString())?.let { graphics.blitSprite(RenderPipelines.GUI_TEXTURED, it, itemX, itemY, size, size) }
            }
        }
        if (highlight) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, highlightX, highlightY, highlightSize, highlightSize)
        if (!e.enabled) graphics.fill(r.x0, r.y0, r.x1, r.y1, DISABLED_SLOT_OVERLAY)
        if (e.selected) {
            graphics.outline(r.x0 - 1, r.y0 - 1, r.width + 2, r.height + 2, SELECTED_FRAME)
            graphics.outline(r.x0, r.y0, r.width, r.height, SELECTED_FRAME_INNER)
        }
        if (hovered) graphics.requestCursor(if (e.enabled) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
    }

    private fun extractScrollbar(graphics: GuiGraphicsExtractor, node: ScreenNode, mouseX: Double, mouseY: Double) {
        val panel = node.element as ScrollPanel
        if (!hasScrollbar(panel)) return
        val bar = scrollbarRect(node)
        Scrollbar.extract(graphics, bar, scrollOffset(panel.id), panel.contentHeight)
        if (scrollbarDrag?.panelId == panel.id) {
            graphics.requestCursor(CursorTypes.RESIZE_NS)
        } else if (isHovered(node, mouseX, mouseY) && bar.contains(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND)
        }
    }

    private fun extractCustom(graphics: GuiGraphicsExtractor, e: Custom, hovered: Boolean, mouseX: Int, mouseY: Int) {
        val custom = customs[e.id] ?: return
        val widget = custom.widget()
        if (widget == null) {
            extractPlaceholder(graphics, custom)
            return
        }
        graphics.enableScissor(custom.x, custom.y, custom.x + custom.width, custom.y + custom.height)
        try {
            val target = GuiDrawTarget(graphics, font, host.textures, mouseX, mouseY)
            widget.render(target, custom.x, custom.y, custom.width, custom.height, custom.properties, hovered, mouseX.toDouble(), mouseY.toDouble())
        } finally {
            graphics.disableScissor()
        }
    }

    /** Stand-in for a custom element without a widget: a magenta box labelled with the widget type. */
    private fun extractPlaceholder(graphics: GuiGraphicsExtractor, custom: CustomElement) {
        val x = custom.x
        val y = custom.y
        val w = custom.width
        val h = custom.height
        graphics.fill(x, y, x + w, y + h, PLACEHOLDER_FILL)
        graphics.outline(x, y, w, h, PLACEHOLDER_BORDER)
        val text = font.plainSubstrByWidth(custom.type.toString(), (w - 4).coerceAtLeast(0))
        if (h >= font.lineHeight + 2) graphics.text(font, text, x + 2, y + (h - font.lineHeight) / 2 + 1, -1, true)
    }

    /**
     * Tooltip of the topmost element under the mouse that has one (its own tooltip and/or the vanilla item tooltip).
     * An interactive element without a tooltip hides the tooltips of elements below it.
     */
    private fun extractTooltip(graphics: GuiGraphicsExtractor, mx: Double, my: Double, mouseX: Int, mouseY: Int) {
        for (node in nodes.asReversed()) {
            if (!isHovered(node, mx, my)) continue
            val ownLines = node.tooltipLines(font)
            val item = itemForTooltip(node)
            if (item != null) {
                extractItemTooltip(graphics, item.first, item.second, ownLines, mouseX, mouseY)
                return
            }
            if (ownLines.isNotEmpty()) {
                graphics.setTooltipForNextFrame(font, ownLines, mouseX, mouseY)
                return
            }
            if (isInteractive(node.placed)) return
        }
    }

    /** The vanilla tooltip of [item] (or its id in red if it failed to decode), its exact count and [ownLines]. */
    private fun extractItemTooltip(
        graphics: GuiGraphicsExtractor, item: DisplayItem, data: ItemStackData, ownLines: List<FormattedCharSequence>, mouseX: Int, mouseY: Int,
    ) {
        val stack = item.stack
        val lines = ArrayList<FormattedCharSequence>()
        if (item.failed) {
            lines += Component.literal(data.itemId.toString()).withStyle(ChatFormatting.RED).visualOrderText
        } else {
            Screen.getTooltipFromItem(minecraft, stack).mapTo(lines) { it.visualOrderText }
        }
        if (item.count > MAX_PLAIN_COUNT) {
            lines += Component.literal("x" + String.format(Locale.ROOT, "%,d", item.count)).withStyle(ChatFormatting.GRAY).visualOrderText
        }
        lines += ownLines
        graphics.setTooltipForNextFrame(
            font, lines, if (item.failed) Optional.empty() else stack.tooltipImage, DefaultTooltipPositioner.INSTANCE,
            mouseX, mouseY, false, if (item.failed) null else stack.get(DataComponents.TOOLTIP_STYLE),
        )
    }

    private fun itemForTooltip(node: ScreenNode): Pair<DisplayItem, ItemStackData>? = when (val e = node.element) {
        is ItemDisplay -> if (e.showItemTooltip) items.get(e.id, e.item) to e.item else null
        is ItemSlot -> e.item?.takeIf { e.showItemTooltip }?.let { items.get(e.id, it) to it }
        else -> null
    }

    // ---- dropdown overlay ---------------------------------------------------------------------------------

    private fun toggleDropdown(head: DropdownHead) {
        if (dropdown?.head === head) {
            closeDropdown()
            return
        }
        if (!head.active || head.options.isEmpty()) return
        dropdown = DropdownOverlay(head, font, ::closeDropdown).also { it.layout(width, height) }
        setFocused(head)
    }

    private fun closeDropdown() {
        dropdown = null
    }

    // ---- input --------------------------------------------------------------------------------------------

    override fun mouseMoved(x: Double, y: Double) {
        val open = dropdown ?: return super.mouseMoved(x, y)
        open.mouseMoved(x, y)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        updateGeometry()
        dropdown?.let {
            it.mouseClicked(event.x(), event.y())
            return true
        }
        if (event.button() == LEFT && pressScrollbar(event.x(), event.y())) return true

        val target = TreeLayout.topmostAt(placed, geometry, event.x(), event.y(), topModalId, ::isInteractive)
        if (target < 0) return clickOutside(event)
        val node = nodes[target]
        val widget = widgetOf(node)
        if (widget != null) {
            clickWidget(widget, event, doubleClick)
        } else {
            setFocused(null)
            clickElement(node.element, event)
        }
        return true
    }

    /**
     * Starts dragging the scrollbar under the mouse; scrollbars are drawn above the panel's children, so they are
     * hit first, innermost panel first. A click into the track moves the thumb there.
     */
    private fun pressScrollbar(mouseX: Double, mouseY: Double): Boolean {
        for (node in nodes.asReversed()) {
            val panel = node.element as? ScrollPanel ?: continue
            if (!hasScrollbar(panel) || !isHovered(node, mouseX, mouseY)) continue
            val bar = scrollbarRect(node)
            if (!bar.contains(mouseX, mouseY)) continue
            val current = scrollOffset(panel.id)
            val offset = ScrollMath.offsetForPress(current, mouseY - bar.y0, panel.contentHeight, bar.height)
            if (offset != current) setScroll(panel, offset)
            scrollbarDrag = ScrollbarDrag(panel.id, mouseY, offset)
            return true
        }
        return false
    }

    /** A click on no interactive element: blocked while a modal is open, otherwise it may drive server keybinds. */
    private fun clickOutside(event: MouseButtonEvent): Boolean {
        setFocused(null)
        if (topModalId != null) return true
        ServerKeybinds.onScreenMouse(event, true)
        return false
    }

    private fun clickWidget(widget: AbstractWidget, event: MouseButtonEvent, doubleClick: Boolean) {
        if (widget.mouseClicked(event, doubleClick)) {
            if (widget.shouldTakeFocusAfterInteraction()) setFocused(widget)
            if (event.button() == LEFT) isDragging = true
        } else if (focused !== widget) {
            setFocused(null)
        }
    }

    private fun clickElement(element: Element, event: MouseButtonEvent) {
        val button = MouseButtons.toProtocol(event.button())
        when (element) {
            is ItemSlot -> if (element.enabled) sendClick(element.id, button, event)
            is Panel -> if (button >= 0) {
                playClickSound()
                sendClick(element.id, button, event)
            }
            is Custom -> clickCustom(element, button, event)
            else -> {}
        }
    }

    /** Hands the press to the element's Lua widget, which decides whether the server hears of it. */
    private fun clickCustom(element: Custom, button: Int, event: MouseButtonEvent) {
        val custom = customs[element.id] ?: return
        val widget = custom.widget()
        val report = if (widget == null) {
            true
        } else {
            pressedCustom = custom to event.button()
            widget.mouseClicked(custom.width, custom.height, custom.properties, event.x() - custom.x, event.y() - custom.y, button)
        }
        if (report) sendClick(custom.elementId, button, event)
    }

    /** Reports a click on [elementId] to the server ([button] in protocol numbering; `-1` is not reported). */
    private fun sendClick(elementId: String, button: Int, event: MouseButtonEvent) {
        if (button >= 0) host.send(ScreenClick(screenId, elementId, button, Modifiers.of(event)))
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        releaseCustom(event)
        if (dropdown?.endDrag() == true) return true
        if (scrollbarDrag != null) {
            scrollbarDrag = null
            return true
        }
        ServerKeybinds.onScreenMouse(event, false)
        return super.mouseReleased(event)
    }

    /** Hands the release of the pressed button to the Lua widget that got the press (if it still exists). */
    private fun releaseCustom(event: MouseButtonEvent) {
        val (custom, button) = pressedCustom ?: return
        if (button != event.button()) return
        pressedCustom = null
        updateGeometry()
        if (customs[custom.elementId] === custom) {
            custom.widget()?.mouseReleased(
                custom.width, custom.height, custom.properties, event.x() - custom.x, event.y() - custom.y, MouseButtons.toProtocol(button),
            )
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        updateGeometry()
        dropdown?.let {
            it.mouseDragged(event.y())
            return true
        }
        scrollbarDrag?.let { drag ->
            dragScrollbar(drag, event.y())
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    private fun dragScrollbar(drag: ScrollbarDrag, mouseY: Double) {
        val panel = nodes.firstOrNull { it.element.id == drag.panelId }?.element as? ScrollPanel ?: return
        setScroll(panel, ScrollMath.dragOffset(drag.startOffset, mouseY - drag.startMouseY, panel.contentHeight, panel.bounds.height))
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        updateGeometry()
        dropdown?.let {
            it.mouseScrolled(x, y, scrollY)
            return true
        }
        if (scrollY == 0.0) return false
        if (scrollCustom(x, y, scrollY) || scrollPanel(x, y, scrollY)) return true
        return topModalId != null
    }

    /** Offers the wheel to the Lua widget under the mouse; `true` if it consumed it. */
    private fun scrollCustom(x: Double, y: Double, scrollY: Double): Boolean {
        val index = TreeLayout.topmostAt(placed, geometry, x, y, topModalId) { it.element is Custom }
        if (index < 0) return false
        val custom = customs[nodes[index].element.id] ?: return false
        val widget = custom.widget() ?: return false
        return widget.mouseScrolled(custom.width, custom.height, custom.properties, x - custom.x, y - custom.y, scrollY)
    }

    /** Scrolls the innermost scroll panel under the mouse that can still move in that direction. */
    private fun scrollPanel(x: Double, y: Double, scrollY: Double): Boolean {
        for (node in nodes.asReversed()) {
            val panel = node.element as? ScrollPanel ?: continue
            if (!isHovered(node, x, y)) continue
            val current = scrollOffset(panel.id)
            val next = ScrollMath.clamp(current - scrollY * ScrollMath.WHEEL_STEP, panel.contentHeight, panel.bounds.height)
            if (next != current) {
                setScroll(panel, next)
                return true
            }
        }
        return false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        dropdown?.let {
            it.keyPressed(event)
            return true
        }
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
        val scrollParent = node.placed.scrollParentIndex
        if (scrollParent < 0) return
        val panelPlacement = placed[scrollParent]
        val panel = panelPlacement.element as ScrollPanel
        val top = node.placed.offsetY - panelPlacement.offsetY
        val offset = ScrollMath.ensureVisible(scrollOffset(panel.id), top, top + node.placed.height, panel.contentHeight, panel.bounds.height)
        setScroll(panel, offset)
    }

    private fun hasTextFieldFocus(): Boolean = (focused as? EditBox)?.canConsumeInput() == true

    override fun tick() {
        flushInputs(force = false)
    }

    override fun removed() {
        if (closeSilently) return
        flushInputs(force = true)
        host.send(ScreenClosed(screenId))
    }

    /** Reports pending text field and slider values ([force]: regardless of the throttle). */
    private fun flushInputs(force: Boolean) {
        fields.values.forEach { it.flush(force) }
        sliders.values.forEach { it.flush(force) }
    }

    override fun isPauseScreen(): Boolean = false

    private fun identifier(id: String): Identifier? = Identifier.tryParse(id)

    private companion object {
        /** Mouse position handed to widgets that must not react to the mouse (blocked / under an open list). */
        const val OFFSCREEN = -100_000

        /** Left mouse button in 26.3 (SDL) numbering. */
        const val LEFT = 1

        /** Item models are 16x16; vanilla slot highlight sprites are 24x24 around them. */
        const val ITEM_SIZE = 16
        const val SLOT_HIGHLIGHT_SIZE = 24

        /** Counts above this are abbreviated on the slot ([CountFormat]); the tooltip shows the exact number. */
        const val MAX_PLAIN_COUNT = 999

        const val DISABLED_SLOT_OVERLAY = 0x90202020.toInt()
        const val SELECTED_FRAME = 0xFFFFFFFF.toInt()
        const val SELECTED_FRAME_INNER = 0x80FFFFFF.toInt()
        const val PLACEHOLDER_FILL = 0x80400040.toInt()
        const val PLACEHOLDER_BORDER = 0xFFFF00FF.toInt()

        val SLOT_SPRITE: Identifier = Identifier.withDefaultNamespace("container/slot")
        val SLOT_HIGHLIGHT_BACK: Identifier = Identifier.withDefaultNamespace("container/slot_highlight_back")
        val SLOT_HIGHLIGHT_FRONT: Identifier = Identifier.withDefaultNamespace("container/slot_highlight_front")
    }
}
