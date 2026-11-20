package dev.kiln.example.server.shop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopValidationTest {

    @Test
    fun `valid listing`() {
        val v = ShopValidation.listing(hasItem = true, available = 64, amountText = "32", priceText = " 15 ", category = Category.ORES)
        assertTrue(v.ok)
        assertEquals(32, v.amount)
        assertEquals(15L, v.price)
    }

    @Test
    fun `missing item and empty fields`() {
        val v = ShopValidation.listing(false, 0, "", "", null)
        assertEquals(setOf("item", "amount", "price", "category"), v.errors.keys)
        assertNull(v.amount)
        assertNull(v.price)
    }

    @Test
    fun `amount above inventory count`() {
        val v = ShopValidation.listing(true, 10, "11", "5", Category.BLOCKS)
        assertEquals(setOf("amount"), v.errors.keys)
        assertEquals("Du hast nur 10 Stück.", v.errors["amount"])
    }

    @Test
    fun `item gone from inventory`() {
        val v = ShopValidation.listing(true, 0, "1", "5", Category.BLOCKS)
        assertEquals(setOf("item"), v.errors.keys)
    }

    @Test
    fun `price range and category ALL`() {
        assertEquals(setOf("price"), ShopValidation.listing(true, 5, "1", "0", Category.FOOD).errors.keys)
        assertEquals(setOf("price"), ShopValidation.listing(true, 5, "1", "1000001", Category.FOOD).errors.keys)
        assertEquals(setOf("price", "amount"), ShopValidation.listing(true, 5, "-1", "abc", Category.FOOD).errors.keys)
        assertEquals(setOf("category"), ShopValidation.listing(true, 5, "1", "1", Category.ALL).errors.keys)
    }

    @Test
    fun `price edit`() {
        assertNull(ShopValidation.price("250"))
        assertNotNull(ShopValidation.price(""))
        assertNotNull(ShopValidation.price("0"))
        assertNotNull(ShopValidation.price("9999999"))
    }

    @Test
    fun `formatting`() {
        assertEquals("1.234.567 Taler", ShopFormat.money(1_234_567))
        assertEquals("abgelaufen", ShopFormat.remaining(100, 100))
        assertEquals("unbegrenzt", ShopFormat.remaining(null, 0))
        assertEquals("2 Std. 0 Min.", ShopFormat.remaining(2 * 3_600_000L, 0))
        assertEquals("7 T. 0 Std.", ShopFormat.remaining(ListingDuration.D7.millis, 0))
        assertEquals("1 Min.", ShopFormat.remaining(1, 0))
    }

    @Test
    fun `ellipsize fits the width`() {
        assertEquals("Diamond", ShopFormat.ellipsize("Diamond", 100))
        val cut = ShopFormat.ellipsize("Spitzhacke des Zwergenkönigs", 60)
        assertTrue(cut.endsWith("..."))
        assertTrue(ShopFormat.width(cut) <= 60)
        assertEquals("", ShopFormat.ellipsize("abc", 3))
    }

    @Test
    fun `tile columns grow with the width`() {
        assertEquals(1, ShopLayout.columns(100, 120, 4))
        assertEquals(2, ShopLayout.columns(290, 120, 4))
        assertEquals(3, ShopLayout.columns(370, 120, 4))
        assertEquals(7, ShopLayout.columns(900, 120, 4))
        assertEquals(143, ShopLayout.tileWidth(290, 2, 4))
    }
}
