package dev.kiln.client.lua

import dev.kiln.client.lua.items.ItemRef

class FakeItem(override val id: String) : ItemRef {
    override val name: String = id.substringAfter(':').replace('_', ' ')
    override val maxStackSize: Int = 64
}
