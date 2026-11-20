package dev.kiln.client.screen

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.element.Button
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.Panel
import dev.kiln.protocol.element.ScrollPanel
import dev.kiln.protocol.element.Text
import dev.kiln.protocol.element.TextField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TreeLayoutTest {

    private val tree = Panel(
        "root", Bounds(0, 0, 200, 100),
        children = listOf(
            Text("title", Bounds(5, 5, 100, 10), "\"Hi\""),
            Panel("inner", Bounds(10, 20, 100, 50), children = listOf(Button("ok", Bounds(3, 4, 40, 20), "\"OK\""))),
            TextField("name", Bounds(120, 20, 60, 20)),
        ),
    )

    @Test
    fun `flatten resolves offsets in draw order`() {
        val placed = TreeLayout.flatten(tree)
        assertEquals(listOf("root", "title", "inner", "ok", "name"), placed.map { it.element.id })
        val ok = placed.first { it.element.id == "ok" }
        assertEquals(13, ok.offsetX)
        assertEquals(24, ok.offsetY)
    }

    @Test
    fun `root is centered`() {
        assertEquals(60 to 70, TreeLayout.rootOrigin(tree, 320, 240))
    }

    @Test
    fun `fullscreen root is not centered`() {
        assertEquals(0 to 0, TreeLayout.rootOrigin(tree, 320, 240, fullscreen = true))
    }

    @Test
    fun `flatten descends into scroll panels and modals`() {
        val root = Panel(
            "root", Bounds(0, 0, 200, 100),
            children = listOf(
                dev.kiln.protocol.element.ScrollPanel("list", Bounds(5, 5, 50, 50), listOf(Text("row", Bounds(0, 70, 50, 10), "\"r\"")), contentHeight = 80),
                dev.kiln.protocol.element.Modal("dialog", Bounds(20, 20, 60, 40), listOf(Button("ok", Bounds(2, 3, 20, 10), "\"OK\""))),
            ),
        )
        val placed = TreeLayout.flatten(root).associateBy { it.element.id }
        assertEquals(5 to 75, placed.getValue("row").let { it.offsetX to it.offsetY })
        assertEquals(22 to 23, placed.getValue("ok").let { it.offsetX to it.offsetY })
    }

    @Test
    fun `pool keeps objects by id, recreates incompatible and disposes vanished`() {
        val disposed = ArrayList<String>()
        val pool = KeyedPool<Pair<String, String>, PooledThing>(
            idOf = { it.first },
            create = { PooledThing(it.first, it.second) },
            update = { w, s -> w.kind = s.second; w.updates++ },
            dispose = { disposed += it.id },
            compatible = { w, s -> w.kind == s.second },
        )
        val first = pool.sync(listOf("a" to "button", "b" to "field", "c" to "button"))
        val second = pool.sync(listOf("b" to "field", "a" to "field", "d" to "button"))
        assertSame(first[1], second[0]) // b kept
        assertEquals(2, second[0].updates)
        assertNotSame(first[0], second[1]) // a changed kind -> recreated
        assertEquals(listOf("a", "c"), disposed)
        assertEquals(listOf("b", "a", "d"), second.map { it.id })
        assertEquals(3, pool.size)
    }

    @Test
    fun `mouse buttons map from SDL to protocol numbers`() {
        assertEquals(0, MouseButtons.toProtocol(1))
        assertEquals(2, MouseButtons.toProtocol(2))
        assertEquals(1, MouseButtons.toProtocol(3))
        assertEquals(3, MouseButtons.toProtocol(4))
        assertEquals(7, MouseButtons.toProtocol(8))
        assertEquals(-1, MouseButtons.toProtocol(9))
        assertEquals(-1, MouseButtons.toProtocol(0))
    }
}
