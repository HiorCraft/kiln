package dev.kiln.server.runtime.screen

import dev.kiln.protocol.message.ModalDismiss
import dev.kiln.protocol.message.ScreenClick
import dev.kiln.protocol.message.ScreenInput
import dev.kiln.server.api.screen.screen
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventValidatorTest {
    private val tree = ScreenConverter.convert(
        screen(200, 100) {
            text("label", id = "label")
            button("on", "On")
            button("off", "Off") { enabled = false }
            textField("name") { maxLength = 5 }
            custom("gauge", Key.key("kiln_example", "gauge"), width = 10, height = 10)
        }.render(),
    )

    private fun click(id: String, screenId: Int = 7) = EventValidator.validateClick(ScreenClick(screenId, id, 0), 7, tree)

    private fun input(id: String, value: String = "x", screenId: Int = 7) =
        EventValidator.validateInput(ScreenInput(screenId, id, value), 7, tree)

    @Test
    fun `valid clicks`() {
        assertEquals(Validation.Valid, click("on"))
        assertEquals(Validation.Valid, click("gauge"))
    }

    @Test
    fun `invalid clicks`() {
        assertTrue(click("off") is Validation.Invalid, "disabled button")
        assertTrue(click("label") is Validation.Invalid, "not clickable")
        assertTrue(click("name") is Validation.Invalid, "text field is not clickable")
        assertTrue(click("missing") is Validation.Invalid, "unknown element")
        assertTrue(click("on", screenId = 8) is Validation.Invalid, "other screen")
        assertTrue(EventValidator.validateClick(ScreenClick(7, "on", 0), null, null) is Validation.Invalid, "no screen")
    }

    @Test
    fun `inputs`() {
        assertEquals(Validation.Valid, input("name", "12345"))
        assertTrue(input("name", "123456") is Validation.Invalid, "too long")
        assertTrue(input("on") is Validation.Invalid, "button is not a text field")
        assertTrue(input("missing") is Validation.Invalid)
        assertTrue(input("name", screenId = 1) is Validation.Invalid)
    }

    private val shop = ScreenConverter.convert(
        screen(300, 200) {
            panel(width = 20, height = 20, id = "tile") { onClick { } }
            panel(width = 20, height = 20, id = "plain") { }
            itemSlot("slot")
            itemSlot("locked") { enabled = false }
            dropdown("sort") { option("price", "Price"); option("name", "Name") }
            dropdown("off") { option("a", "A"); enabled = false }
            checkbox("mine")
            slider("amount") { min = 1.0; max = 64.0; step = 1.0; value = 1.0 }
            slider("ratio") { min = 0.0; max = 1.0 }
            textField("count") { numeric(1.0, 100.0) }
            textField("price") { numeric(0.0, 10.0, integer = false) }
        }.render(),
    )

    private fun shopClick(id: String) = EventValidator.validateClick(ScreenClick(7, id, 0), 7, shop)
    private fun shopInput(id: String, value: String) = EventValidator.validateInput(ScreenInput(7, id, value), 7, shop)

    @Test
    fun `shop clicks`() {
        assertEquals(Validation.Valid, shopClick("tile"))
        assertEquals(Validation.Valid, shopClick("slot"))
        assertTrue(shopClick("plain") is Validation.Invalid, "panel without onClick")
        assertTrue(shopClick("locked") is Validation.Invalid, "disabled slot")
        assertTrue(shopClick("sort") is Validation.Invalid, "dropdown is not clickable")
    }

    @Test
    fun `shop inputs`() {
        assertEquals(Validation.Valid, shopInput("sort", "name"))
        assertTrue(shopInput("sort", "nope") is Validation.Invalid, "unknown option")
        assertTrue(shopInput("off", "a") is Validation.Invalid, "disabled dropdown")
        assertEquals(Validation.Valid, shopInput("mine", "true"))
        assertEquals(Validation.Valid, shopInput("mine", "false"))
        assertTrue(shopInput("mine", "yes") is Validation.Invalid)
        assertEquals(Validation.Valid, shopInput("amount", "64"))
        assertEquals(Validation.Valid, shopInput("amount", "12.0"))
        assertTrue(shopInput("amount", "65") is Validation.Invalid, "above max")
        assertTrue(shopInput("amount", "1.5") is Validation.Invalid, "off step")
        assertTrue(shopInput("amount", "NaN") is Validation.Invalid)
        assertEquals(Validation.Valid, shopInput("ratio", "0.3337"))
        assertEquals(Validation.Valid, shopInput("count", ""))
        assertEquals(Validation.Valid, shopInput("count", "100"))
        assertTrue(shopInput("count", "0") is Validation.Invalid, "below min")
        assertTrue(shopInput("count", "2.5") is Validation.Invalid, "not an integer")
        assertTrue(shopInput("count", "abc") is Validation.Invalid)
        assertEquals(Validation.Valid, shopInput("price", "9.99"))
        assertTrue(shopInput("slot", "x") is Validation.Invalid, "slot is not an input")
    }

    private val withModals = ScreenConverter.convert(
        screen(300, 200) {
            button("behind", "Behind")
            textField("behind_field")
            modal("first", 100, 100) { button("first_ok", "OK") }
            modal("second", 100, 100) {
                button("second_ok", "OK")
                textField("second_field")
                panel(width = 50, height = 50, id = "nested") { button("nested_ok", "OK") }
            }
        }.render(),
    )

    @Test
    fun `modal blockade`() {
        fun click(id: String) = EventValidator.validateClick(ScreenClick(7, id, 0), 7, withModals)
        fun input(id: String) = EventValidator.validateInput(ScreenInput(7, id, "x"), 7, withModals)
        assertEquals(Validation.Valid, click("second_ok"))
        assertEquals(Validation.Valid, click("nested_ok"))
        assertEquals(Validation.Valid, input("second_field"))
        assertTrue(click("behind") is Validation.Invalid, "below modal")
        assertTrue(click("first_ok") is Validation.Invalid, "inside a modal that is not on top")
        assertTrue(input("behind_field") is Validation.Invalid)
    }

    @Test
    fun `modal dismiss`() {
        assertEquals(Validation.Valid, EventValidator.validateModalDismiss(ModalDismiss(7, "second"), 7, withModals))
        assertTrue(EventValidator.validateModalDismiss(ModalDismiss(7, "first"), 7, withModals) is Validation.Invalid)
        assertTrue(EventValidator.validateModalDismiss(ModalDismiss(7, "second"), 8, withModals) is Validation.Invalid)
        assertTrue(EventValidator.validateModalDismiss(ModalDismiss(7, "on"), 7, tree) is Validation.Invalid, "no modal")
        assertTrue(EventValidator.validateModalDismiss(ModalDismiss(7, "second"), null, null) is Validation.Invalid)
    }

    @Test
    fun `closed`() {
        assertEquals(Validation.Valid, EventValidator.validateClosed(7, 7))
        assertTrue(EventValidator.validateClosed(6, 7) is Validation.Invalid)
        assertTrue(EventValidator.validateClosed(7, null) is Validation.Invalid)
    }
}
