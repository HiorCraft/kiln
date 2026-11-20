package dev.kiln.server.api.extension

import org.bukkit.inventory.ItemStack
import java.util.Base64

/**
 * Items for Lua extensions: a stack travels inside a channel message as a string and becomes an opaque item handle in
 * Lua via `kiln.items.decode(text)`, which `g:item(handle, x, y)` draws with all data components (name, lore,
 * enchantment glint, item model, ...) and `g:itemTooltip` shows with the vanilla tooltip.
 *
 * Format: standard Base64 (RFC 4648, with padding) of Paper's [ItemStack.serializeAsBytes] (gzip NBT of the vanilla
 * item codec plus `DataVersion`) of the stack **with amount 1**; the count is sent separately (it may exceed 99).
 */
public object ExtensionItems {
    /** Longest accepted encoding: Base64 of 65536 bytes (the client rejects larger items). */
    public const val MAX_ENCODED_LENGTH: Int = 87_384

    /**
     * Encodes [stack] (amount ignored, encoded as 1).
     * @throws IllegalArgumentException for air / empty stacks or items larger than [MAX_ENCODED_LENGTH]
     */
    @JvmStatic
    public fun encode(stack: ItemStack): String {
        require(!stack.isEmpty) { "Cannot encode an empty item stack" }
        val single = stack.clone().also { it.amount = 1 }
        val text = Base64.getEncoder().encodeToString(single.serializeAsBytes())
        require(text.length <= MAX_ENCODED_LENGTH) { "Item too large for an extension message (${text.length} > $MAX_ENCODED_LENGTH chars)" }
        return text
    }
}
