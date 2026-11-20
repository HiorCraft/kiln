package dev.kiln.client.screen

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Button
import dev.kiln.protocol.Modal
import dev.kiln.protocol.NumericRange
import dev.kiln.protocol.Panel
import dev.kiln.protocol.ScrollPanel
import dev.kiln.protocol.Text
import dev.kiln.protocol.topModal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModalAndFormatTest {

    private val tree = Panel(
        "root", Bounds(0, 0, 300, 200),
        children = listOf(
            Modal("first", Bounds(10, 10, 50, 50), listOf(Button("first-ok", Bounds(0, 0, 50, 20), "\"A\""))),
            Button("buy", Bounds(100, 100, 60, 20), "\"Buy\""),
            ScrollPanel(
                "list", Bounds(0, 0, 80, 50), contentHeight = 200,
                children = listOf(Button("row0", Bounds(0, 0, 80, 20), "\"0\""), Button("row5", Bounds(0, 100, 80, 20), "\"5\"")),
            ),
            Modal("confirm", Bounds(90, 90, 100, 60), listOf(Button("yes", Bounds(5, 5, 40, 20), "\"Yes\""))),
        ),
    )

    @Test
    fun `modals are drawn last in their order`() {
        val ids = TreeLayout.flatten(tree).map { it.element.id }
        assertEquals(listOf("root", "buy", "list", "row0", "row5", "first", "first-ok", "confirm", "yes"), ids)
    }

    @Test
    fun `only the topmost modal receives input`() {
        val placed = TreeLayout.flatten(tree).associateBy { it.element.id }
        val top = tree.topModal()?.id
        assertEquals("confirm", top)
        assertTrue(TreeLayout.isBlocked(placed.getValue("buy"), top))
        assertTrue(TreeLayout.isBlocked(placed.getValue("first-ok"), top))
        assertTrue(TreeLayout.isBlocked(placed.getValue("root"), top))
        assertFalse(TreeLayout.isBlocked(placed.getValue("confirm"), top))
        assertFalse(TreeLayout.isBlocked(placed.getValue("yes"), top))
        assertFalse(TreeLayout.isBlocked(placed.getValue("buy"), null))
    }

    @Test
    fun `hit test skips everything below the modal`() {
        val placed = TreeLayout.flatten(tree)
        val geometry = TreeLayout.geometry(placed, 0, 0, { 0 })
        val interactive = { p: PlacedElement -> p.element is Button }
        // (100, 100) is inside "buy" but also inside the confirm modal (not on its button): nothing
        assertEquals(-1, TreeLayout.topmostAt(placed, geometry, 120.0, 140.0, "confirm", interactive))
        val yes = TreeLayout.topmostAt(placed, geometry, 100.0, 100.0, "confirm", interactive)
        assertEquals("yes", placed[yes].element.id)
        // without the modal the button below is hit
        val buy = TreeLayout.topmostAt(placed, geometry, 150.0, 105.0, null, interactive)
        assertEquals("buy", placed[buy].element.id)
    }

    @Test
    fun `scroll offset moves children and clips hits to the panel`() {
        val placed = TreeLayout.flatten(Panel("root", Bounds(0, 0, 300, 200), listOf(tree.children[2])))
        val any = { p: PlacedElement -> p.element is Button }
        val unscrolled = TreeLayout.geometry(placed, 10, 20, { 0 })
        val row5 = placed.indexOfFirst { it.element.id == "row5" }
        assertEquals(Rect(10, 120, 90, 140), unscrolled[row5].rect)
        assertEquals(Rect(10, 20, 90, 70), unscrolled[row5].clip)
        assertEquals(-1, TreeLayout.topmostAt(placed, unscrolled, 50.0, 125.0, null, any)) // below the viewport
        val scrolled = TreeLayout.geometry(placed, 10, 20, { if (it == "list") 90 else 0 })
        assertEquals(Rect(10, 30, 90, 50), scrolled[row5].rect)
        assertEquals(row5, TreeLayout.topmostAt(placed, scrolled, 50.0, 35.0, null, any))
        // row0 scrolled above the viewport: its old position is not clickable any more
        assertEquals(-1, TreeLayout.topmostAt(placed, scrolled, 50.0, 25.0, null, any))
    }

    @Test
    fun `nested scroll panels intersect their clips`() {
        val root = Panel(
            "root", Bounds(0, 0, 200, 200),
            listOf(
                ScrollPanel(
                    "outer", Bounds(0, 0, 100, 100), contentHeight = 400,
                    children = listOf(
                        ScrollPanel("inner", Bounds(0, 80, 100, 60), contentHeight = 300, children = listOf(Text("t", Bounds(0, 0, 10, 10), "\"t\""))),
                    ),
                ),
            ),
        )
        val placed = TreeLayout.flatten(root)
        val geometry = TreeLayout.geometry(placed, 0, 0, { if (it == "outer") 50 else 10 })
        val t = geometry[placed.indexOfFirst { it.element.id == "t" }]
        assertEquals(Rect(0, 20, 10, 30), t.rect) // 80 - 50 - 10
        assertEquals(Rect(0, 30, 100, 90), t.clip) // inner (30..90) within outer (0..100)
        assertFalse(t.hit(5.0, 25.0))
    }

    @Test
    fun `fullscreen root size override`() {
        val placed = TreeLayout.flatten(Panel("root", Bounds(0, 0, 300, 200)))
        assertEquals(Rect(0, 0, 427, 240), TreeLayout.geometry(placed, 0, 0, { 0 }, 427 to 240)[0].rect)
        assertNull(TreeLayout.geometry(placed, 0, 0, { 0 })[0].clip)
    }

    @Test
    fun `item counts are abbreviated and never rounded up`() {
        assertEquals("1", CountFormat.format(1))
        assertEquals(null, CountFormat.decoration(1))
        assertEquals("64", CountFormat.decoration(64))
        assertEquals("999", CountFormat.format(999))
        assertEquals("1k", CountFormat.format(1_000))
        assertEquals("1.2k", CountFormat.format(1_250))
        assertEquals("9.9k", CountFormat.format(9_999))
        assertEquals("12k", CountFormat.format(12_345))
        assertEquals("999k", CountFormat.format(999_999))
        assertEquals("1M", CountFormat.format(1_000_000))
        assertEquals("2.5M", CountFormat.format(2_500_000))
        assertEquals("999M", CountFormat.format(999_999_999))
        assertEquals("1B", CountFormat.format(1_000_000_000))
    }

    @Test
    fun `slider values snap to the step and format plainly`() {
        val whole = SliderMath(0.0, 64.0, 1.0)
        assertEquals(0, whole.decimals)
        assertEquals(13.0, whole.snap(12.6))
        assertEquals("13", whole.format(whole.snap(12.6)))
        assertEquals(64.0, whole.fromNormalized(1.0))
        assertEquals(0.5, whole.toNormalized(32.0))

        val tenth = SliderMath(0.0, 1.0, 0.1)
        assertEquals(1, tenth.decimals)
        assertEquals(0.3, tenth.snap(0.1 + 0.2))
        assertEquals("0.3", tenth.format(tenth.snap(0.29)))
        assertEquals("1", tenth.format(1.0))

        val coarse = SliderMath(0.0, 10.0, 3.0)
        assertEquals(10.0, coarse.snap(10.0)) // max stays reachable although it is off the grid
        assertEquals(9.0, coarse.snap(8.0))

        val big = SliderMath(0.0, 1.0e12, 1.0)
        assertEquals("100000000000", big.format(1.0e11)) // no exponent

        val continuous = SliderMath(-1.0, 1.0, 0.0)
        assertEquals("-0.25", continuous.format(continuous.snap(-0.2512)))
        assertEquals("0", continuous.format(-0.0001))
    }

    @Test
    fun `numeric text input accepts only number prefixes`() {
        val positive = NumericRange(1.0, 999.0, integer = true)
        assertTrue(NumericInput.isPartial("", positive))
        assertTrue(NumericInput.isPartial("12", positive))
        assertFalse(NumericInput.isPartial("-1", positive))
        assertFalse(NumericInput.isPartial("1.5", positive))
        assertFalse(NumericInput.isPartial("1e3", positive))
        assertTrue(NumericInput.isPartial("0", positive)) // allowed while typing ...
        assertFalse(NumericInput.reportable("0", positive)) // ... but never reported
        assertTrue(NumericInput.reportable("", positive))
        assertTrue(NumericInput.reportable("999", positive))
        assertFalse(NumericInput.reportable("1000", positive))

        val decimal = NumericRange(-10.0, 10.0, integer = false)
        assertTrue(NumericInput.isPartial("-", decimal))
        assertTrue(NumericInput.isPartial("-.", decimal))
        assertTrue(NumericInput.isPartial("-2.5", decimal))
        assertFalse(NumericInput.isPartial("2.5.1", decimal))
        assertFalse(NumericInput.reportable("-", decimal))
        assertTrue(NumericInput.reportable("-2.5", decimal))
    }
}
