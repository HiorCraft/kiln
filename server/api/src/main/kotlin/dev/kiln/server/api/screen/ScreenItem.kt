package dev.kiln.server.api.screen

import net.kyori.adventure.key.Key
import org.bukkit.inventory.ItemStack

/**
 * An item to show: either just a type ([OfType], works without a running server, e.g. in tests) or a full
 * [ItemStack] ([OfStack]) with all its data components (name, lore, enchantments, item model, ...).
 * [count] is the displayed amount, 1..1,000,000,000 (above 99 drawn as abbreviated text); it is independent of the
 * stack's own amount.
 */
public sealed interface ScreenItem {
    public val count: Int

    public data class OfType(val type: Key, override val count: Int = 1) : ScreenItem {
        init {
            requireCount(count)
        }
    }

    /** Use [ScreenItem.of], which copies the stack; do not mutate [stack] afterwards. */
    public data class OfStack(val stack: ItemStack, override val count: Int) : ScreenItem {
        init {
            requireCount(count)
            require(!stack.isEmpty) { "Empty item stacks cannot be displayed" }
        }
    }

    public companion object {
        public const val MAX_COUNT: Int = 1_000_000_000

        /** A copy of [stack]; [count] defaults to the stack amount. */
        @JvmStatic
        public fun of(stack: ItemStack, count: Int = stack.amount): ScreenItem = OfStack(stack.clone(), count)

        @JvmStatic
        public fun of(type: Key, count: Int = 1): ScreenItem = OfType(type, count)

        /** `null` for `null`, air or empty stacks. */
        @JvmStatic
        public fun ofNullable(stack: ItemStack?, count: Int? = null): ScreenItem? =
            stack?.takeUnless { it.isEmpty }?.let { of(it, count ?: it.amount) }

        private fun requireCount(count: Int) {
            require(count in 1..MAX_COUNT) { "Item count out of range: $count" }
        }
    }
}
