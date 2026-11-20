package dev.kiln.server.runtime.extension

import dev.kiln.protocol.ResourceId
import org.bukkit.entity.Player

/** What the registry needs from the bridge. */
interface ExtensionMessenger {
    fun sendExtensionMessage(player: Player, channel: ResourceId, json: String)
}
