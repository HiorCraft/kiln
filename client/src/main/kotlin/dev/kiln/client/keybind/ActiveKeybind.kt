package dev.kiln.client.keybind

import net.minecraft.client.KeyMapping

/**
 * A registered runtime keybind: persisted as [storeId], owned by the server ([extension] `null`, edges are sent) or
 * by a Lua extension (edges go to [handler]); [localId] is the owner's own id for it.
 */
internal class ActiveKeybind(
    val storeId: String,
    val mapping: KeyMapping,
    val defaultKeyName: String,
    val extension: String?,
    val localId: String,
    val handler: KeybindHandler?,
)
