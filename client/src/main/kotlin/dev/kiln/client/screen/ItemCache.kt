package dev.kiln.client.screen

import dev.kiln.protocol.ItemStackData

/** Per-element cache: decodes only when the element is new or its item type / NBT bytes changed (a count change is free). */
class ItemCache {
    private val entries = HashMap<String, CachedItem>()

    fun get(elementId: String, data: ItemStackData): DisplayItem {
        val cached = entries[elementId]
        if (cached != null && cached.data.itemId == data.itemId && cached.data.nbt.contentEquals(data.nbt)) {
            if (cached.data.count == data.count) return cached.item
            val recounted = DisplayItem(cached.item.stack, data.count.coerceAtLeast(1), cached.item.failed)
            entries[elementId] = CachedItem(data, recounted)
            return recounted
        }
        val item = ItemStacks.decode(data)
        entries[elementId] = CachedItem(data, item)
        return item
    }

    /** Drops entries of element ids that no longer exist. */
    fun retain(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    fun clear() = entries.clear()
}
