package dev.kiln.server.runtime.extension

import dev.kiln.server.api.extension.ExtensionChannel
import dev.kiln.protocol.ResourceId
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import kotlin.io.path.extension
import kotlin.io.path.name

class ChannelImpl<T : Any>(
    override val extension: ExtensionImpl,
    val name: String,
    private val type: Class<T>,
    val handler: (ExtensionChannel<T>.(player: Player, message: T) -> Unit)?,
) : ExtensionChannel<T> {
    val resourceId: ResourceId = ResourceId.of(extension.id, name)

    override val key: Key = Key.key(extension.id, name)

    override fun send(player: Player, message: T) {
        if (!player.isOnline) return
        extension.send(player, resourceId, ExtensionJson.encode(message))
    }

    override fun broadcast(message: T) {
        val json = ExtensionJson.encode(message)
        Bukkit.getOnlinePlayers().forEach { extension.send(it, resourceId, json) }
    }

    /**
     * Decodes [json] and runs the handler. @throws JsonParseException for invalid JSON (caller drops + logs);
     * returns `false` if there is no handler or the message was `null`.
     */
    fun receive(player: Player, json: String): Boolean {
        val handler = handler ?: return false
        val message = ExtensionJson.decode<T>(json, type) ?: return false
        handler(this, player, message)
        return true
    }

    override fun unregister() {
        if (extension.channels[name] === this) extension.channels.remove(name)
    }

    override fun toString(): String = "ExtensionChannel($key)"
}
