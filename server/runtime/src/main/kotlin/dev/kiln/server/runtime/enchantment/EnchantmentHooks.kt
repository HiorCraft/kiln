package dev.kiln.server.runtime.enchantment

import net.kyori.adventure.key.Key

/** Pure helpers of the enchantment hooks (no Bukkit). */
object EnchantmentHooks {
    /** The level that counts when an enchantment is on several worn/held items: the highest one. */
    fun highestLevel(levels: List<Int>): Int = levels.maxOrNull()?.coerceAtLeast(0) ?: 0

    /** Bootstrap-registered enchantments whose namespace has no extension (no translations or assets). */
    fun warnings(registered: Set<Key>, extensionIds: Set<String>): List<String> =
        registered.filter { it.namespace() !in extensionIds }.sortedBy { it.asString() }.map {
            "Enchantment ${it.asString()}: no extension '${it.namespace()}' registered; its texts and assets will be missing"
        }

    /** Runs every handler; an exception of one is reported to [onError] and does not stop the others. */
    inline fun dispatch(handlers: List<() -> Unit>, onError: (Throwable) -> Unit) {
        for (handler in handlers) {
            try {
                handler()
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}
