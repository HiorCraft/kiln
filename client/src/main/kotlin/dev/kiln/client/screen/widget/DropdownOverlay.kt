package dev.kiln.client.screen.widget

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.screen.DropdownState
import dev.kiln.client.screen.KilnScreen
import dev.kiln.client.screen.Rect
import dev.kiln.client.screen.ScrollMath
import dev.kiln.client.screen.Scrollbar
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

/**
 * The option list of an open [DropdownHead], drawn by the [KilnScreen] above everything else. While it is open it
 * takes all input: choosing an option, Escape, Tab or a click outside the list call [close].
 */
internal class DropdownOverlay(val head: DropdownHead, private val font: Font, private val close: () -> Unit) {
    private val state = DropdownState(head.options.size, MAX_ROWS, head.selectedIndex)
    private var list: Rect = Rect(0, 0, 0, 0)

    /** Scrollbar drag in progress: mouse y and scroll offset (px) at its start. */
    private var drag: Pair<Double, Double>? = null

    private val contentHeight: Int get() = state.optionCount * ROW_HEIGHT
    private val scrollOffset: Double get() = state.scrollTop * ROW_HEIGHT.toDouble()

    /** Places the list below the head (above if there is more room there) and fits the visible row count. */
    fun layout(screenWidth: Int, screenHeight: Int) {
        val count = head.options.size
        val labelWidth = head.labels.maxOfOrNull(font::width) ?: 0
        val scrollbarSpace = if (count > MAX_ROWS) ScrollMath.SCROLLBAR_WIDTH + 1 else 0
        val listWidth = maxOf(head.width, labelWidth + 12 + scrollbarSpace).coerceAtMost((screenWidth - 4).coerceAtLeast(1))
        val below = screenHeight - (head.y + head.height) - 2
        val above = head.y - 2
        val wanted = minOf(count, MAX_ROWS) * ROW_HEIGHT + 2
        val placeAbove = wanted > below && above > below
        val space = if (placeAbove) above else below
        val rows = minOf(count, MAX_ROWS, ((space - 2) / ROW_HEIGHT).coerceAtLeast(1))
        val listHeight = rows * ROW_HEIGHT + 2
        val x = head.x.coerceAtMost(screenWidth - listWidth - 2).coerceAtLeast(2)
        val y = if (placeAbove) head.y - listHeight else head.y + head.height
        list = Rect.of(x, y, listWidth, listHeight)
        if (state.optionCount != count || state.visibleRows != rows) state.resize(count, rows)
    }

    private fun scrollbar(): Rect? {
        if (!state.isScrollable) return null
        return Rect(list.x1 - 1 - ScrollMath.SCROLLBAR_WIDTH, list.y0 + 1, list.x1 - 1, list.y1 - 1)
    }

    /** Option index of the row under the mouse, or `-1`. */
    private fun optionAt(mouseX: Double, mouseY: Double): Int {
        if (!list.contains(mouseX, mouseY) || scrollbar()?.contains(mouseX, mouseY) == true) return -1
        val row = ((mouseY - list.y0 - 1) / ROW_HEIGHT).toInt()
        return state.optionAtRow(row)
    }

    private fun scrollTo(offset: Double) = state.scrollTo(Math.round(offset / ROW_HEIGHT).toInt())

    fun extract(graphics: GuiGraphicsExtractor, mouseX: Double, mouseY: Double) {
        graphics.fill(list.x0, list.y0, list.x1, list.y1, BACKGROUND)
        graphics.outline(list.x0, list.y0, list.width, list.height, BORDER)
        val bar = scrollbar()
        val textRight = (bar?.x0 ?: (list.x1 - 1)) - 2
        graphics.enableScissor(list.x0 + 1, list.y0 + 1, textRight, list.y1 - 1)
        for (row in 0 until state.visibleRows) {
            val index = state.optionAtRow(row)
            if (index < 0) break
            extractRow(graphics, row, index, textRight)
        }
        graphics.disableScissor()
        if (bar != null) Scrollbar.extract(graphics, bar, scrollOffset, contentHeight)
        if (list.contains(mouseX, mouseY)) graphics.requestCursor(CursorTypes.POINTING_HAND)
    }

    private fun extractRow(graphics: GuiGraphicsExtractor, row: Int, index: Int, textRight: Int) {
        val rowY = list.y0 + 1 + row * ROW_HEIGHT
        if (index == state.highlighted) graphics.fill(list.x0 + 1, rowY, textRight + 2, rowY + ROW_HEIGHT, HIGHLIGHT)
        val label = head.labels.getOrNull(index) ?: Component.literal(head.options[index].value)
        val color = if (index == head.selectedIndex) SELECTED_TEXT else -1
        graphics.text(font, label, list.x0 + 5, rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1, color, true)
    }

    fun mouseMoved(mouseX: Double, mouseY: Double) {
        val index = optionAt(mouseX, mouseY)
        if (index >= 0 && index != state.highlighted) state.highlight(index)
    }

    fun mouseClicked(mouseX: Double, mouseY: Double) {
        val bar = scrollbar()
        if (bar != null && bar.contains(mouseX, mouseY)) {
            scrollTo(ScrollMath.offsetForPress(scrollOffset, mouseY - bar.y0, contentHeight, bar.height))
            drag = mouseY to scrollOffset
            return
        }
        val index = optionAt(mouseX, mouseY)
        if (index >= 0) {
            playClickSound()
            head.choose(index)
            close()
        } else if (!list.contains(mouseX, mouseY)) {
            close() // click outside (also on the head itself) closes the list
        }
    }

    fun mouseDragged(mouseY: Double) {
        val (startY, startOffset) = drag ?: return
        val bar = scrollbar() ?: return
        scrollTo(ScrollMath.dragOffset(startOffset, mouseY - startY, contentHeight, bar.height))
    }

    /** Ends a scrollbar drag; `false` if there was none. */
    fun endDrag(): Boolean {
        if (drag == null) return false
        drag = null
        return true
    }

    fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double) {
        if (list.contains(mouseX, mouseY) && scrollY != 0.0) state.scrollBy(if (scrollY > 0) -1 else 1)
    }

    fun keyPressed(event: KeyEvent) {
        when {
            event.isEscape -> close()
            event.isUp -> state.move(-1)
            event.isDown -> state.move(1)
            event.key() == InputConstants.KEY_PAGEUP -> state.pageUp()
            event.key() == InputConstants.KEY_PAGEDOWN -> state.pageDown()
            event.key() == InputConstants.KEY_HOME -> state.first()
            event.key() == InputConstants.KEY_END -> state.last()
            event.isConfirmation || event.isSelection -> {
                if (state.highlighted >= 0) head.choose(state.highlighted)
                close()
            }
            event.isCycleFocus -> close()
        }
    }

    private companion object {
        const val MAX_ROWS = 10
        const val ROW_HEIGHT = 12
        const val BACKGROUND = 0xF0101010.toInt()
        const val BORDER = 0xFFA0A0A0.toInt()
        const val HIGHLIGHT = 0x50FFFFFF
        const val SELECTED_TEXT = 0xFFFFFF55.toInt()
    }
}
