package dev.kiln.client.screen

import dev.kiln.protocol.ItemStackData

/** An [ItemCache] entry: the element's item [data] and what it decoded to. */
internal class CachedItem(val data: ItemStackData, val item: DisplayItem)
