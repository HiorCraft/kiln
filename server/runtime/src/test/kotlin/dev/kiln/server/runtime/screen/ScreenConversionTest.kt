package dev.kiln.server.runtime.screen

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Color
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.codec.ElementCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.element.Button
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.element.CheckboxStyle
import dev.kiln.protocol.element.Custom
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.Image
import dev.kiln.protocol.element.ItemDisplay
import dev.kiln.protocol.element.ItemSlot
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.NumericRange
import dev.kiln.protocol.element.Panel
import dev.kiln.protocol.element.ProgressBar
import dev.kiln.protocol.element.ScrollPanel
import dev.kiln.protocol.element.Slider
import dev.kiln.protocol.element.Text
import dev.kiln.protocol.element.TextField
import dev.kiln.protocol.message.Viewport
import dev.kiln.server.api.screen.Layout
import dev.kiln.server.api.screen.TextAlign
import dev.kiln.server.api.screen.fullscreenScreen
import dev.kiln.server.api.screen.screen
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color as BukkitColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenConversionTest {

    private fun convert(width: Int = 200, height: Int = 100, content: dev.kiln.server.api.screen.ScreenBuilder.() -> Unit) =
        ScreenConverter.convert(screen(width, height, content).render())

    @Test
    fun `absolute layout keeps positions`() {
        val root = convert {
            text("a", x = 5, y = 7, width = 50)
            button("b", "B", x = 10, y = 30, width = 40, height = 20)
        }.root
        assertEquals(Bounds(0, 0, 200, 100), root.bounds)
        assertEquals(listOf(Bounds(5, 7, 50, 9), Bounds(10, 30, 40, 20)), root.children.map { it.bounds })
    }

    @Test
    fun `column stacks children with padding and gap, x is an indent`() {
        val root = convert {
            layout = Layout.Column(gap = 3, padding = 4)
            text("title", width = 100, height = 10, y = 999)
            progressBar(0.5f, width = 100, height = 5)
            button("b", "B", x = 6, width = 50, height = 20)
        }.root
        assertEquals(
            listOf(Bounds(4, 4, 100, 10), Bounds(4, 17, 100, 5), Bounds(10, 25, 50, 20)),
            root.children.map { it.bounds },
        )
    }

    @Test
    fun `row places children left to right, y is an offset`() {
        val root = convert {
            layout = Layout.Row(gap = 2, padding = 1)
            button("a", "A", width = 30, height = 20, x = 500)
            button("b", "B", width = 40, height = 20, y = 3)
            text("xyz") // auto width: estimated 3 * 6
            button("c", "C", width = 10, height = 20)
        }.root
        assertEquals(
            listOf(Bounds(1, 1, 30, 20), Bounds(33, 4, 40, 20), Bounds(75, 1, 0, 9), Bounds(95, 1, 10, 20)),
            root.children.map { it.bounds },
        )
    }

    @Test
    fun `nested panels resolve their own layout relative to the parent`() {
        val root = convert {
            layout = Layout.Column(gap = 0, padding = 0)
            text("header", width = 100, height = 10)
            panel(width = 100, height = 50, id = "inner") {
                layout = Layout.Row(gap = 5, padding = 2)
                button("x", "X", width = 20, height = 20)
                button("y", "Y", width = 20, height = 20)
            }
        }.root
        val inner = root.children[1] as Panel
        assertEquals(Bounds(0, 10, 100, 50), inner.bounds)
        assertEquals(listOf(Bounds(2, 2, 20, 20), Bounds(27, 2, 20, 20)), inner.children.map { it.bounds })
    }

    @Test
    fun `converts every element type`() {
        var clicked = false
        val converted = convert {
            background = BukkitColor.fromARGB(0x80, 0x10, 0x20, 0x30)
            border = BukkitColor.fromRGB(0xFFFFFF)
            text(Component.text("Hi", NamedTextColor.RED), width = 20, color = BukkitColor.fromRGB(0x123456), align = TextAlign.CENTER, shadow = false)
            button("btn", "Go") {
                enabled = false
                tooltip = Component.text("tip")
                onClick { clicked = true }
            }
            image(Key.key("kiln_example", "textures/gui/factory_icon.png"), width = 32, height = 32)
            item(Key.key("diamond"), count = 3)
            progressBar(2f) // clamped
            textField("name") {
                value = "abc"
                maxLength = 16
                placeholder = Component.text("Name")
                onInput { }
            }
            custom("gauge", Key.key("kiln_example", "gauge"), width = 40, height = 40) {
                property("value", 0.25)
                property("label", "Energy")
            }
        }
        val c = converted.root.children
        assertEquals(Color.argb(0x80, 0x10, 0x20, 0x30), converted.root.background)
        assertEquals(Color.WHITE, converted.root.border)

        val text = c[0] as Text
        assertEquals(Color.rgb(0x123456), text.color)
        assertEquals(dev.kiln.protocol.TextAlign.CENTER, text.align)
        assertTrue(text.text.contains("\"Hi\"") && text.text.contains("red"), text.text)

        val button = c[1] as Button
        assertEquals("btn", button.id)
        assertEquals(false, button.enabled)
        assertTrue(button.tooltip!!.contains("tip"))

        val image = c[2] as Image
        assertEquals(ResourceId.of("kiln_example", "textures/gui/factory_icon.png"), image.texture)
        assertEquals(32, image.textureWidth)

        val item = c[3] as ItemDisplay
        assertEquals(ResourceId.of("minecraft", "diamond"), item.item.itemId)
        assertEquals(3, item.item.count)
        assertNull(item.item.nbt)

        assertEquals(1f, (c[4] as ProgressBar).value)

        val field = c[5] as TextField
        assertEquals("abc", field.value)
        assertEquals(16, field.maxLength)

        val custom = c[6] as Custom
        assertEquals(ResourceId.of("kiln_example", "gauge"), custom.type)
        assertEquals(mapOf("value" to "0.25", "label" to "Energy"), custom.properties)

        assertEquals(setOf("btn"), converted.clickHandlers.keys)
        assertEquals(setOf("name"), converted.inputHandlers.keys)
        converted.clickHandlers.getValue("btn").invoke(dummyClick())
        assertTrue(clicked)
        assertTrue(converted.elements.keys.containsAll(listOf("root", "btn", "name", "gauge", "auto:0")))
    }

    @Test
    fun `converted tree survives the wire codec`() {
        val root = convert {
            layout = Layout.Column()
            text("a")
            button("b", "B")
        }.root
        val writer = ProtocolWriter()
        ElementCodec.encode(writer, root)
        assertEquals(root, ElementCodec.decode(ProtocolReader(writer.toByteArray())))
    }

    @Test
    fun `protocol limit violations become IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            convert {
                // deeper than MAX_DEPTH
                fun dev.kiln.server.api.screen.ContainerBuilder.nest(n: Int) {
                    if (n > 0) panel(width = 1, height = 1) { nest(n - 1) }
                }
                nest(20)
            }
        }
        assertThrows<IllegalArgumentException> {
            convert { panel(x = 100_000, width = 1, height = 1) {} }
        }
    }

    @Test
    fun `no handlers for elements without them`() {
        val converted = convert { button("b", "B") }
        assertNull(converted.clickHandlers["b"])
    }

    @Test
    fun `fullscreen roots take the viewport size`() {
        val definition = fullscreenScreen {
            text("${viewport.width}x${viewport.height}", id = "size")
            button("corner", "X", x = width - 20, y = height - 20, width = 20, height = 20)
        }
        val rendered = definition.render(dev.kiln.server.api.screen.Viewport(640, 360))
        assertTrue(rendered.fullscreen)
        val converted = ScreenConverter.convert(rendered)
        assertTrue(converted.fullscreen)
        assertEquals(Bounds(0, 0, 640, 360), converted.root.bounds)
        assertEquals(Bounds(620, 340, 20, 20), converted.elements.getValue("corner").bounds)
        assertTrue((converted.elements.getValue("size") as Text).text.contains("640x360"))
        // fixed screens ignore the viewport (apart from exposing it)
        assertEquals(Bounds(0, 0, 200, 100), ScreenConverter.convert(screen(200, 100) {}.render(dev.kiln.server.api.screen.Viewport(640, 360))).root.bounds)
    }

    @Test
    fun `scroll panels compute their content height`() {
        val root = convert {
            scrollPanel("auto", width = 100, height = 50) {
                layout = Layout.Column(gap = 2, padding = 4)
                repeat(10) { text("row $it", width = 90, height = 10) }
            }
            scrollPanel("fixed", width = 100, height = 50, contentHeight = 500) {
                text("deep", y = 30_000, width = 10)
            }
        }.root
        val auto = root.children[0] as dev.kiln.protocol.element.ScrollPanel
        assertEquals(4 + 10 * 10 + 9 * 2 + 4, auto.contentHeight) // bottom of the last row plus padding
        assertEquals(10, auto.children.size)
        val fixed = root.children[1] as dev.kiln.protocol.element.ScrollPanel
        assertEquals(500, fixed.contentHeight)
        assertEquals(30_000, fixed.children[0].bounds.y)
    }

    @Test
    fun `modals, clickable panels and inputs`() {
        val converted = convert(width = 300, height = 200) {
            panel(width = 50, height = 50, id = "tile") {
                hoverBackground = BukkitColor.WHITE
                selected = true
                tooltip = Component.text("tile")
                onClick { }
            }
            dropdown("sort") {
                option("price", "Price")
                option("name", Component.text("Name"))
                selected = "name"
                onChange { }
            }
            checkbox("mine") { checked = true; style = dev.kiln.server.api.screen.CheckboxStyle.TOGGLE; onChange { } }
            slider("amount") { min = 1.0; max = 64.0; step = 1.0; value = 100.0; onChange { } }
            textField("price") { value = "5"; numeric(0.0, 1000.0) }
            modal("confirm", width = 100, height = 60) {
                button("yes", "Yes")
                onDismiss { }
            }
        }
        val tile = converted.elements.getValue("tile") as Panel
        assertTrue(tile.clickable && tile.selected && tile.hoverBackground == Color.WHITE && tile.tooltip!!.contains("tile"))
        val dropdown = converted.elements.getValue("sort") as dev.kiln.protocol.element.Dropdown
        assertEquals(listOf("price", "name"), dropdown.options.map { it.value })
        assertEquals("name", dropdown.selected)
        val checkbox = converted.elements.getValue("mine") as dev.kiln.protocol.element.Checkbox
        assertEquals(dev.kiln.protocol.element.CheckboxStyle.TOGGLE, checkbox.style)
        assertEquals(64.0, (converted.elements.getValue("amount") as dev.kiln.protocol.element.Slider).value) // clamped
        assertEquals(dev.kiln.protocol.element.NumericRange(0.0, 1000.0, true), (converted.elements.getValue("price") as TextField).numeric)
        val modal = converted.elements.getValue("confirm") as dev.kiln.protocol.element.Modal
        assertEquals(Bounds(100, 70, 100, 60), modal.bounds) // centered
        assertEquals("confirm", converted.topModal?.id)
        assertEquals("confirm", converted.modalOf["yes"])
        assertNull(converted.modalOf["tile"])
        assertEquals(setOf("tile"), converted.clickHandlers.keys)
        assertEquals(setOf("sort", "mine", "amount"), converted.inputHandlers.keys)
        assertEquals(setOf("confirm"), converted.dismissHandlers.keys)

        assertThrows<IllegalArgumentException> { convert { panel(width = 1, height = 1) { onClick { } } } }
        assertThrows<IllegalArgumentException> { convert { dropdown("d") { option("a", "A"); selected = "b" } } }
        assertThrows<IllegalArgumentException> { convert { dropdown("d") { option("a", "A"); option("a", "B") } } }
    }

    @Test
    fun `inventory grid lays out 36 slots`() {
        var clickedSlot = -1
        val converted = convert(width = 200, height = 120) {
            inventoryGrid({ null }, x = 5, y = 10, idPrefix = "inv") {
                slots { slot, _ -> enabled = slot != 0 }
                onClick { clickedSlot = it.slot }
            }
        }
        val grid = converted.elements.getValue("inv") as Panel
        assertEquals(Bounds(5, 10, 162, 76), grid.bounds)
        assertEquals(36, grid.children.size)
        assertEquals(Bounds(0, 0, 18, 18), converted.elements.getValue("inv/9").bounds)
        assertEquals(Bounds(144, 36, 18, 18), converted.elements.getValue("inv/35").bounds)
        assertEquals(Bounds(0, 58, 18, 18), converted.elements.getValue("inv/0").bounds)
        val hotbar0 = converted.elements.getValue("inv/0") as dev.kiln.protocol.element.ItemSlot
        assertEquals(false, hotbar0.enabled)
        assertNull(hotbar0.item)
        converted.clickHandlers.getValue("inv/17").invoke(dummyClick())
        assertEquals(17, clickedSlot)
    }

    private fun dummyClick(): dev.kiln.server.api.screen.ClickContext {
        // handlers in this test do not touch the context; a null-safe stand-in is built via a proxy
        val player = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(org.bukkit.entity.Player::class.java),
        ) { _, _, _ -> null } as org.bukkit.entity.Player
        val handle = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(dev.kiln.server.api.screen.ScreenHandle::class.java),
        ) { _, _, _ -> null } as dev.kiln.server.api.screen.ScreenHandle
        return dev.kiln.server.api.screen.ClickContext(player, handle, "btn", dev.kiln.server.api.screen.MouseButton.LEFT)
    }
}
