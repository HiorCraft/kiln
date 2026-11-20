package dev.kiln.client.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrollAndDropdownTest {

    @Test
    fun `scroll offset is clamped to the content`() {
        assertEquals(0.0, ScrollMath.clamp(-5.0, 300, 100))
        assertEquals(200.0, ScrollMath.clamp(999.0, 300, 100))
        assertEquals(42.0, ScrollMath.clamp(42.0, 300, 100))
        // nothing to scroll when the content fits
        assertEquals(0.0, ScrollMath.clamp(50.0, 80, 100))
        assertFalse(ScrollMath.canScroll(100, 100))
        assertEquals(0.0, ScrollMath.clamp(Double.NaN, 300, 100))
    }

    @Test
    fun `content shrinking clamps a kept offset`() {
        val before = ScrollMath.clamp(500.0, 1000, 100)
        assertEquals(500.0, before)
        assertEquals(150.0, ScrollMath.clamp(before, 250, 100))
    }

    @Test
    fun `thumb is proportional with a minimum and maps back when dragged`() {
        assertEquals(100, ScrollMath.thumbHeight(50, 100))
        assertEquals(50, ScrollMath.thumbHeight(200, 100))
        assertEquals(32, ScrollMath.thumbHeight(10_000, 100))
        assertEquals(0, ScrollMath.thumbY(0.0, 200, 100))
        assertEquals(50, ScrollMath.thumbY(100.0, 200, 100)) // bottom: track = 100 - 50
        // dragging the thumb over the whole track scrolls over the whole content
        assertEquals(100.0, ScrollMath.dragOffset(0.0, 50.0, 200, 100))
        assertEquals(100.0, ScrollMath.dragOffset(0.0, 500.0, 200, 100))
        assertEquals(0.0, ScrollMath.dragOffset(100.0, -500.0, 200, 100))
        // a track click centers the thumb at the mouse
        assertEquals(50.0, ScrollMath.offsetForTrackClick(50.0, 200, 100))
    }

    @Test
    fun `pressing the thumb keeps the offset, pressing the track jumps there`() {
        assertEquals(0.0, ScrollMath.offsetForPress(0.0, 20.0, 200, 100))
        assertEquals(100.0, ScrollMath.offsetForPress(0.0, 80.0, 200, 100))
        assertEquals(ScrollMath.offsetForTrackClick(10.0, 200, 100), ScrollMath.offsetForPress(100.0, 10.0, 200, 100))
    }

    @Test
    fun `ensure visible scrolls minimally`() {
        assertEquals(0.0, ScrollMath.ensureVisible(0.0, 10, 30, 500, 100))
        assertEquals(50.0, ScrollMath.ensureVisible(0.0, 130, 150, 500, 100))
        assertEquals(20.0, ScrollMath.ensureVisible(80.0, 20, 40, 500, 100))
        assertEquals(400.0, ScrollMath.ensureVisible(0.0, 480, 520, 500, 100))
    }

    @Test
    fun `dropdown opens with the selection visible and navigates without wrapping`() {
        val state = DropdownState(optionCount = 30, visibleRows = 10, initialIndex = 20)
        assertEquals(20, state.highlighted)
        assertTrue(state.highlighted in state.scrollTop until state.scrollTop + 10)
        state.move(1)
        assertEquals(21, state.highlighted)
        state.last()
        assertEquals(29, state.highlighted)
        assertEquals(20, state.scrollTop)
        state.move(1)
        assertEquals(29, state.highlighted)
        state.first()
        assertEquals(0, state.highlighted)
        assertEquals(0, state.scrollTop)
        state.move(-1)
        assertEquals(0, state.highlighted)
        state.pageDown()
        assertEquals(10, state.highlighted)
        assertEquals(1, state.scrollTop)
    }

    @Test
    fun `dropdown without selection starts at the first or last option`() {
        val down = DropdownState(5, 10)
        assertEquals(-1, down.highlighted)
        down.move(1)
        assertEquals(0, down.highlighted)
        val up = DropdownState(5, 10)
        up.move(-1)
        assertEquals(4, up.highlighted)
        assertFalse(up.isScrollable)
    }

    @Test
    fun `dropdown wheel scrolling and row mapping`() {
        val state = DropdownState(15, 5, initialIndex = 0)
        state.scrollBy(3)
        assertEquals(3, state.scrollTop)
        assertEquals(0, state.highlighted) // highlight may scroll out of view
        assertEquals(5, state.optionAtRow(2))
        assertEquals(-1, state.optionAtRow(5))
        state.scrollBy(100)
        assertEquals(10, state.scrollTop)
        assertEquals(14, state.optionAtRow(4))
        state.resize(optionCount = 8, visibleRows = 5)
        assertEquals(0, state.highlighted)
        assertEquals(0, state.scrollTop) // the kept highlight is scrolled back into view
    }

    @Test
    fun `empty dropdown is inert`() {
        val state = DropdownState(0, 5, initialIndex = 3)
        state.move(1)
        assertEquals(-1, state.highlighted)
        assertEquals(-1, state.optionAtRow(0))
    }
}
