package dev.kiln.example.server.shop

import java.util.UUID

// Pure shop model (no Bukkit types): used by the Paper layer with I = ItemStack and by the tests with I = String.

/**
 * One offer. [item] is a single-item template (amount 1), [amount] how many are left. [sellerId] `null` = server
 * offer. [name] is the plain, searchable name (custom name or English item name).
 */
data class Offer<I>(
    val id: Long,
    val item: I,
    val name: String,
    val itemKey: String,
    val category: Category,
    val pricePerUnit: Long,
    val amount: Int,
    val sellerId: UUID?,
    val sellerName: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val notifyOnSale: Boolean = false,
) {
    fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
    fun isActive(now: Long): Boolean = amount > 0 && !isExpired(now)
}
