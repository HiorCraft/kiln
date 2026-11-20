package dev.kiln.example.server.shop

/**
 * Inventory arithmetic on an abstract slot list (pure, testable). A slot is `null` (empty) or a [SlotStack] that
 * either is [similar] to the item being moved or not. The Paper layer maps the player's storage slots (0..35)
 * to this model and applies the resulting plans in the same tick, so what was computed is what happens.
 */
data class SlotStack(val similar: Boolean, val amount: Int)

/** Set [slot] to [newAmount] of the moved item (`0` = empty the slot); [wasEmpty] = create a new stack there. */
data class SlotChange(val slot: Int, val newAmount: Int, val wasEmpty: Boolean)

data class InsertPlan(val changes: List<SlotChange>, val leftover: Int)

object InventoryMath {

    /** How many more items of the moved kind fit into [slots] with stacks of at most [maxStack]. */
    fun freeSpace(slots: List<SlotStack?>, maxStack: Int): Long {
        require(maxStack >= 1) { "maxStack must be positive" }
        return slots.sumOf { slot ->
            when {
                slot == null || slot.amount <= 0 -> maxStack.toLong()
                slot.similar -> (maxStack - slot.amount).coerceAtLeast(0).toLong()
                else -> 0L
            }
        }
    }

    /** Total amount of the moved kind in [slots]. */
    fun countSimilar(slots: List<SlotStack?>): Int =
        slots.sumOf { if (it != null && it.similar && it.amount > 0) it.amount.toLong() else 0L }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * Distributes [amount] items: first topping up similar stacks, then filling empty slots, both in slot order
     * (like vanilla pickup). Whatever does not fit is [InsertPlan.leftover] (to be dropped).
     */
    fun planInsert(slots: List<SlotStack?>, amount: Int, maxStack: Int): InsertPlan {
        require(amount >= 0) { "amount must not be negative" }
        require(maxStack >= 1) { "maxStack must be positive" }
        var left = amount
        val changes = ArrayList<SlotChange>()
        for ((index, slot) in slots.withIndex()) {
            if (left == 0) break
            if (slot == null || !slot.similar || slot.amount <= 0) continue
            val add = minOf(left, maxStack - slot.amount)
            if (add <= 0) continue
            changes += SlotChange(index, slot.amount + add, wasEmpty = false)
            left -= add
        }
        for ((index, slot) in slots.withIndex()) {
            if (left == 0) break
            if (slot != null && slot.amount > 0) continue
            val add = minOf(left, maxStack)
            changes += SlotChange(index, add, wasEmpty = true)
            left -= add
        }
        return InsertPlan(changes, left)
    }

    /**
     * Takes [amount] similar items, starting with [preferredSlot] (the one the player picked), then in slot order.
     * `null` if there are not enough; nothing must be changed then.
     */
    fun planRemove(slots: List<SlotStack?>, amount: Int, preferredSlot: Int? = null): List<SlotChange>? {
        require(amount >= 0) { "amount must not be negative" }
        if (countSimilar(slots) < amount) return null
        var left = amount
        val changes = ArrayList<SlotChange>()
        val order = buildList {
            if (preferredSlot != null && preferredSlot in slots.indices) add(preferredSlot)
            addAll(slots.indices.filter { it != preferredSlot })
        }
        for (index in order) {
            if (left == 0) break
            val slot = slots[index] ?: continue
            if (!slot.similar || slot.amount <= 0) continue
            val take = minOf(left, slot.amount)
            changes += SlotChange(index, slot.amount - take, wasEmpty = false)
            left -= take
        }
        return changes
    }
}
