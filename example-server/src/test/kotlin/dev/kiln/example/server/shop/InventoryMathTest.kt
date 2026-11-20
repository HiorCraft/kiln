package dev.kiln.example.server.shop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InventoryMathTest {
    private fun same(n: Int) = SlotStack(true, n)
    private fun other(n: Int = 64) = SlotStack(false, n)

    /** Applies a plan to a list of similar amounts (null = empty, -1 = foreign item) for easy assertions. */
    private fun applied(slots: List<SlotStack?>, changes: List<SlotChange>): List<Int?> {
        val result = slots.map { if (it == null) null else if (it.similar) it.amount else -1 }.toMutableList()
        for (c in changes) result[c.slot] = c.newAmount.takeIf { it > 0 }
        return result
    }

    @Test
    fun `free space counts empty slots and partial similar stacks only`() {
        val slots = listOf(null, same(60), other(), same(64), other(1), null)
        assertEquals(64 + 4 + 64L, InventoryMath.freeSpace(slots, 64))
        assertEquals(16 + 0 + 16L, InventoryMath.freeSpace(listOf(null, same(16), null), 16))
    }

    @Test
    fun `full inventory has no space`() {
        val slots = List(36) { other() }
        assertEquals(0L, InventoryMath.freeSpace(slots, 64))
        val plan = InventoryMath.planInsert(slots, 5, 64)
        assertEquals(5, plan.leftover)
        assertEquals(emptyList<SlotChange>(), plan.changes)
    }

    @Test
    fun `insert tops up similar stacks before using empty slots`() {
        val slots = listOf(null, same(60), other(), same(30), null)
        val plan = InventoryMath.planInsert(slots, 50, 64)
        assertEquals(0, plan.leftover)
        assertEquals(listOf(12, 64, -1, 64, null), applied(slots, plan.changes))
        val plan2 = InventoryMath.planInsert(slots, 100, 64)
        assertEquals(listOf(62, 64, -1, 64, null), applied(slots, plan2.changes))
    }

    @Test
    fun `insert respects small max stack sizes and reports leftover`() {
        val slots = listOf(null, same(1), other(), null)
        val plan = InventoryMath.planInsert(slots, 5, 1)
        assertEquals(3, plan.leftover)
        assertEquals(listOf(1, 1, -1, 1), applied(slots, plan.changes))
    }

    @Test
    fun `oversized stacks are not counted as space`() {
        assertEquals(0L, InventoryMath.freeSpace(listOf(same(99)), 64))
        assertEquals(1, InventoryMath.planInsert(listOf(same(99)), 1, 64).leftover)
    }

    @Test
    fun `remove takes from the preferred slot first`() {
        val slots = listOf(same(10), other(), same(20), same(5))
        val plan = InventoryMath.planRemove(slots, 25, preferredSlot = 2)!!
        assertEquals(listOf(5, -1, null, 5), applied(slots, plan))
    }

    @Test
    fun `remove whole stack empties the slot`() {
        val slots = listOf(same(10), same(64))
        val plan = InventoryMath.planRemove(slots, 64, preferredSlot = 1)!!
        assertEquals(listOf(10, null), applied(slots, plan))
    }

    @Test
    fun `remove without enough items changes nothing`() {
        val slots = listOf(same(10), other(), null)
        assertNull(InventoryMath.planRemove(slots, 11, 0))
        assertEquals(10, InventoryMath.countSimilar(slots))
    }

    @Test
    fun `remove ignores a preferred slot holding something else`() {
        val slots = listOf(other(), same(3), same(3))
        assertEquals(listOf(-1, null, 2), applied(slots, InventoryMath.planRemove(slots, 4, preferredSlot = 0)!!))
    }

    @Test
    fun `remove then insert restores the amount (no duplication)`() {
        val slots = listOf(same(7), null, same(64), other())
        val removed = InventoryMath.planRemove(slots, 40, 2)!!
        val after = applied(slots, removed).map { if (it == null) null else if (it < 0) other() else same(it) }
        assertEquals(31, InventoryMath.countSimilar(after))
        val back = InventoryMath.planInsert(after, 40, 64)
        assertEquals(0, back.leftover)
        assertEquals(71, applied(after, back.changes).filterNotNull().filter { it > 0 }.sum())
    }
}
