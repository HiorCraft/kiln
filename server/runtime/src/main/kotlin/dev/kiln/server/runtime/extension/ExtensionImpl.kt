package dev.kiln.server.runtime.extension

import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionChannel
import dev.kiln.protocol.Ids
import dev.kiln.protocol.ResourceId
import dev.kiln.server.runtime.item.ContentExtension
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import kotlin.io.path.name

class ExtensionImpl(
    private val registry: ExtensionRegistry,
    override val plugin: Plugin,
    val pkg: ExtensionPackage,
) : Extension {
    internal val channels = LinkedHashMap<String, ChannelImpl<*>>()

    override val id: String get() = pkg.id
    override val version: String get() = pkg.version
    override val name: String get() = pkg.name
    override val permissions: Set<String> get() = pkg.permissions.toSet()
    override val dependencies: List<String> get() = pkg.dependencies.map { it.id }
    override val sha256: String get() = pkg.sha256Hex
    override val size: Int get() = pkg.zip.size

    override fun <T : Any> channel(
        name: String,
        type: Class<T>,
        handler: (ExtensionChannel<T>.(player: Player, message: T) -> Unit)?,
    ): ExtensionChannel<T> {
        require(Ids.isValidExtensionName(name)) { "Invalid channel name '$name' (allowed: [a-z0-9_]{1,32})" }
        if (handler != null && "channels" !in pkg.permissions) {
            registry.logger.warning("Extension $id has no 'channels' permission; its Lua code cannot use channel '$name'")
        }
        val channel = ChannelImpl(this, name, type, handler)
        channels[name] = channel
        return channel
    }

    override fun widget(name: String): Key {
        require(Ids.isValidExtensionName(name)) { "Invalid widget name '$name' (allowed: [a-z0-9_]{1,32})" }
        return Key.key(id, name)
    }

    override fun unregister() = registry.unregister(this)

    /** What content registrations check against: the registering plugin and the package's files. */
    fun asContentExtension(): ContentExtension = ContentExtension(plugin.name, pkg.files.toSet())

    internal fun send(player: Player, channel: ResourceId, json: String) = registry.send(player, channel, json)

    override fun toString(): String = "Extension($id ${pkg.version})"
}
