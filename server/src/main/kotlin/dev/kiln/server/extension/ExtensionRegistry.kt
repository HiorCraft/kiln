package dev.kiln.server.extension

import dev.kiln.api.extension.Extension
import dev.kiln.api.extension.ExtensionChannel
import dev.kiln.api.extension.ExtensionSource
import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** What the registry needs from the bridge. */
interface ExtensionMessenger {
    fun sendExtensionMessage(player: Player, channel: ResourceId, json: String)
}

/**
 * All registered Lua extension packages. Mutated on the main thread; [delivery] is an immutable snapshot that the
 * asynchronous configuration handshakes read: the packages in dependency (= load) order, or the dependency problems
 * (missing dependency, version mismatch, cycle) because of which every joining player is disconnected.
 */
class ExtensionRegistry(
    internal val logger: Logger,
    private val messenger: ExtensionMessenger,
    /** At most this many packages fit into one manifest; more are rejected when they are registered. */
    private val maxExtensions: Int = ProtocolLimits.MAX_MANIFEST_EXTENSIONS,
) {
    private val byId = LinkedHashMap<String, ExtensionImpl>()

    /** What joining players get: [packages] in load order, or [problems] (then nobody can join). */
    data class Delivery(val packages: List<ExtensionPackage>, val problems: List<String>)

    @Volatile
    var delivery: Delivery = Delivery(emptyList(), emptyList())
        private set

    /** Packages in delivery (dependency) order; empty while [Delivery.problems] exist. */
    val packages: List<ExtensionPackage> get() = delivery.packages

    /** Logs the number of extensions and the current dependency problems (called once all plugins are enabled). */
    fun logProblems() {
        logger.info("${byId.size} of at most $maxExtensions extensions registered")
        for (problem in delivery.problems) logger.severe("Extension dependencies: $problem (players cannot join)")
    }

    fun all(): Collection<Extension> = byId.values.toList()

    fun get(id: String): Extension? = byId[id]

    fun register(plugin: Plugin, id: String, source: ExtensionSource): ExtensionImpl {
        check(id !in byId) { "Extension '$id' is already registered (by ${byId[id]?.plugin?.name})" }
        // checked here instead of when the manifest is sent: otherwise every join would fail with an internal error
        if (byId.size >= maxExtensions) {
            throw InvalidExtensionException(
                "Too many extensions: '$id' would be number ${byId.size + 1}, but at most $maxExtensions fit into one " +
                    "manifest (ProtocolLimits.MAX_MANIFEST_EXTENSIONS); '$id' is not registered",
            )
        }
        val files = when (source) {
            is ExtensionSource.Directory -> ExtensionFiles.fromDirectory(source.path)
            is ExtensionSource.Zip -> ExtensionFiles.fromZip(source.path, id)
            is ExtensionSource.PluginResource -> ExtensionFiles.fromJar(jarOf(source.plugin), source.path)
        }
        val pkg = ExtensionPackager.build(id, files)
        val extension = ExtensionImpl(this, plugin, pkg)
        byId[id] = extension
        snapshot()
        logger.info("Registered extension ${pkg.id} ${pkg.version} (${pkg.zip.size} bytes, sha256 ${pkg.sha256Hex.take(12)}..., permissions ${pkg.permissions}) from ${plugin.name}")
        if (Bukkit.getServer() != null && Bukkit.getOnlinePlayers().isNotEmpty()) {
            logger.warning("Extension ${pkg.id} registered while players are online; they get it after reconnecting")
        }
        return extension
    }

    /** Loads every `<id>/` directory and `<id>.zip` in [dir]; invalid packages are logged and skipped. */
    fun loadDirectory(owner: Plugin, dir: Path) {
        Files.createDirectories(dir)
        val entries = dir.listDirectoryEntries().sortedBy { it.name }
        for (entry in entries) {
            val isZip = !entry.isDirectory() && entry.extension.equals("zip", ignoreCase = true)
            if (!entry.isDirectory() && !isZip) continue
            val id = if (isZip) entry.nameWithoutExtension else entry.name
            try {
                register(owner, id, if (isZip) ExtensionSource.Zip(entry) else ExtensionSource.Directory(entry))
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Skipping extension package $entry: ${e.message}")
            }
        }
    }

    internal fun unregister(extension: ExtensionImpl) {
        if (byId[extension.id] !== extension) return
        byId.remove(extension.id)
        extension.channels.clear()
        snapshot()
    }

    /** Removes the extensions [plugin] registered and every channel handler defined by its classes. */
    fun removeAll(plugin: Plugin) {
        byId.values.filter { it.plugin === plugin }.forEach(::unregister)
        val loader = plugin.javaClass.classLoader
        for (extension in byId.values) {
            extension.channels.values.removeIf { it.handler?.javaClass?.classLoader === loader }
        }
    }

    fun clear() {
        byId.values.forEach { it.channels.clear() }
        byId.clear()
        snapshot()
    }

    fun channel(key: ResourceId): ChannelImpl<*>? = byId[key.namespace]?.channels?.get(key.path)

    private fun snapshot() {
        val resolution = ExtensionDependencies.resolve(byId.values.map { it.pkg })
        delivery = Delivery(resolution.order, resolution.problems)
    }

    internal fun send(player: Player, channel: ResourceId, json: String) = messenger.sendExtensionMessage(player, channel, json)

    private fun jarOf(plugin: Plugin): Path {
        val location = plugin.javaClass.protectionDomain?.codeSource?.location
            ?: throw InvalidExtensionException("Cannot locate the jar of ${plugin.name}")
        val path = Paths.get(location.toURI())
        if (path.isDirectory()) throw InvalidExtensionException("${plugin.name} is not loaded from a jar ($path)")
        return path
    }
}

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

    internal fun send(player: Player, channel: ResourceId, json: String) = registry.send(player, channel, json)

    override fun toString(): String = "Extension($id ${pkg.version})"
}

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
