package dev.kiln.server.runtime.extension

import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionSource
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
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
    /** All packages together, unpacked (clients hold them in memory); more is rejected when registering. */
    private val maxTotalUnpackedBytes: Long = ProtocolLimits.MAX_TOTAL_UNPACKED_BYTES,
) {
    private val byId = LinkedHashMap<String, ExtensionImpl>()

    /** Called after [ExtensionImpl.unregister]: content in that namespace (items, furniture, tabs) goes with it. */
    var onUnregistered: (id: String) -> Unit = {}

    @Volatile
    var delivery: ExtensionDelivery = ExtensionDelivery(emptyList(), emptyList())
        private set

    /** Packages in delivery (dependency) order; empty while [ExtensionDelivery.problems] exist. */
    val packages: List<ExtensionPackage> get() = delivery.packages

    /** Logs the number of extensions and the current dependency problems (called once all plugins are enabled). */
    fun logProblems() {
        logger.info("${byId.size} of at most $maxExtensions extensions registered")
        for (problem in delivery.problems) logger.severe("Extension dependencies: $problem (players cannot join)")
    }

    fun all(): Collection<Extension> = byId.values.toList()

    fun get(id: String): Extension? = byId[id]

    /** The registered extension [id] with its package (content registrations check ownership and assets). */
    fun impl(id: String): ExtensionImpl? = byId[id]

    fun register(plugin: Plugin, id: String, source: ExtensionSource): ExtensionImpl {
        check(id !in byId) { "Extension '$id' is already registered (by ${byId[id]?.plugin?.name})" }
        requireManifestRoom(id)
        val pkg = ExtensionPackager.build(id, readFiles(id, source))
        requireTotalSizeWith(pkg)
        val extension = ExtensionImpl(this, plugin, pkg)
        byId[id] = extension
        snapshot()
        logger.info("Registered extension ${pkg.id} ${pkg.version} (${pkg.zip.size} bytes, sha256 ${pkg.sha256Hex.take(12)}..., permissions ${pkg.permissions}) from ${plugin.name}")
        if (Bukkit.getServer() != null && Bukkit.getOnlinePlayers().isNotEmpty()) {
            logger.warning("Extension ${pkg.id} registered while players are online; they get it after reconnecting")
        }
        return extension
    }

    /** Checked here instead of when the manifest is sent: otherwise every join would fail with an internal error. */
    private fun requireManifestRoom(id: String) {
        if (byId.size < maxExtensions) return
        throw InvalidExtensionException(
            "Too many extensions: '$id' would be number ${byId.size + 1}, but at most $maxExtensions fit into one " +
                "manifest (ProtocolLimits.MAX_MANIFEST_EXTENSIONS); '$id' is not registered",
        )
    }

    private fun readFiles(id: String, source: ExtensionSource): Map<String, ByteArray> = when (source) {
        is ExtensionSource.Directory -> ExtensionFiles.fromDirectory(source.path)
        is ExtensionSource.Zip -> ExtensionFiles.fromZip(source.path, id)
        is ExtensionSource.PluginResource -> ExtensionFiles.fromJar(jarOf(source.plugin), source.path)
    }

    private fun requireTotalSizeWith(pkg: ExtensionPackage) {
        val total = byId.values.sumOf { it.pkg.unpackedSize } + pkg.unpackedSize
        if (total <= maxTotalUnpackedBytes) return
        throw InvalidExtensionException(
            "Extensions too large: with '${pkg.id}' all packages would be $total bytes unpacked, at most $maxTotalUnpackedBytes " +
                "(ProtocolLimits.MAX_TOTAL_UNPACKED_BYTES); '${pkg.id}' is not registered",
        )
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
        onUnregistered(extension.id)
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
        delivery = ExtensionDelivery(resolution.order, resolution.problems)
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
