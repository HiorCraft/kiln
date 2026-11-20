package dev.kiln.example.server.shop.lua

import java.util.UUID

/** Items are strings (`"diamond"`); a slot holds item + amount; stacks of 64. */
internal class FakeInventory : ShopInventoryAccess<String> {
    val slots = HashMap<UUID, Array<Pair<String, Int>?>>()
    val dropped = HashMap<UUID, Int>()

    fun of(player: UUID) = slots.getOrPut(player) { arrayOfNulls(36) }

    override fun freeSpace(player: UUID, item: String): Long = of(player).sumOf { s ->
        when {
            s == null -> 64L
            s.first == item -> (64 - s.second).toLong()
            else -> 0L
        }
    }

    override fun give(player: UUID, item: String, amount: Int): Int {
        val inv = of(player)
        var left = amount
        for (i in inv.indices) {
            val s = inv[i]
            if (s != null && s.first == item && s.second < 64) {
                val add = minOf(left, 64 - s.second); inv[i] = item to s.second + add; left -= add
            }
        }
        for (i in inv.indices) {
            if (left == 0) break
            if (inv[i] == null) { val add = minOf(left, 64); inv[i] = item to add; left -= add }
        }
        dropped.merge(player, left, Int::plus)
        return left
    }

    override fun slot(player: UUID, slot: Int): SlotContent<String>? {
        val s = of(player).getOrNull(slot) ?: return null
        return SlotContent(s.first, s.second, "minecraft:${s.first}", s.first, s.first.replaceFirstChar { it.uppercase() })
    }

    override fun countSimilar(player: UUID, template: String): Int = of(player).sumOf { if (it?.first == template) it.second else 0 }

    override fun take(player: UUID, template: String, amount: Int, preferredSlot: Int): Boolean {
        if (countSimilar(player, template) < amount) return false
        val inv = of(player)
        var left = amount
        for (i in listOf(preferredSlot) + inv.indices.filter { it != preferredSlot }) {
            val s = inv[i] ?: continue
            if (s.first != template || left == 0) continue
            val take = minOf(left, s.second)
            inv[i] = if (s.second - take == 0) null else template to s.second - take
            left -= take
        }
        return true
    }
}
