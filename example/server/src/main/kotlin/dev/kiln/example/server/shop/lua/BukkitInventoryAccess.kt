package dev.kiln.example.server.shop.lua

import dev.kiln.example.server.shop.InventoryMath
import dev.kiln.example.server.shop.ShopService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** [ShopInventoryAccess] on the storage slots (0..35) of online players; armor and offhand are never touched. */
internal class BukkitInventoryAccess(private val dsl: ShopService) : ShopInventoryAccess<ItemStack> {
    private fun player(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    override fun freeSpace(player: UUID, item: ItemStack): Long = player(player)?.let { dsl.freeSpace(it, item) } ?: 0L

    override fun give(player: UUID, item: ItemStack, amount: Int): Int {
        val p = player(player) ?: return amount
        return dsl.give(p, item, amount)
    }

    override fun slot(player: UUID, slot: Int): SlotContent<ItemStack>? {
        val p = player(player) ?: return null
        if (slot !in 0 until LuaShopController.STORAGE_SLOTS) return null
        val stack = p.inventory.getItem(slot)?.takeUnless { it.isEmpty } ?: return null
        val one = stack.asOne()
        return SlotContent(one, stack.amount, stack.type.key.asString(), stack.type.key.value(), dsl.plainName(one))
    }

    override fun countSimilar(player: UUID, template: ItemStack): Int = player(player)?.let { dsl.countInInventory(it, template) } ?: 0

    override fun take(player: UUID, template: ItemStack, amount: Int, preferredSlot: Int): Boolean {
        val p = player(player) ?: return false
        val plan = InventoryMath.planRemove(dsl.slots(p, template), amount, preferredSlot) ?: return false
        dsl.apply(p, template, plan)
        return true
    }
}
