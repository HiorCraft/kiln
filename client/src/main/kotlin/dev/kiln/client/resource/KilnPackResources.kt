package dev.kiln.client.resource

import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.MetadataSectionType
import net.minecraft.server.packs.repository.KnownPack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.IoSupplier
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Optional

/**
 * The `assets/` trees of the connected server's extensions as one in-memory client resource pack. [files] maps
 * paths relative to `assets/` (`<namespace>/models/item/ruby.json`) to their content; they were validated before
 * ([dev.kiln.protocol.AssetRules]: own namespace only, allowed types, no `kiln:` references).
 */
class KilnPackResources(private val files: Map<String, ByteArray>) : PackResources {
    private val namespaces: Set<String> = files.keys.mapTo(LinkedHashSet()) { it.substringBefore('/') }

    override fun location(): PackLocationInfo = LOCATION

    override fun getRootResource(vararg path: String): IoSupplier<InputStream>? = null

    override fun getResource(type: PackType, id: Identifier): IoSupplier<InputStream>? {
        if (type != PackType.CLIENT_RESOURCES) return null
        val bytes = files["${id.namespace}/${id.path}"] ?: return null
        return IoSupplier { ByteArrayInputStream(bytes) }
    }

    override fun listResources(type: PackType, namespace: String, path: String, output: PackResources.ResourceOutput) {
        if (type != PackType.CLIENT_RESOURCES) return
        val prefix = "$namespace/$path/"
        for ((file, bytes) in files) {
            if (!file.startsWith(prefix)) continue
            val id = Identifier.tryBuild(namespace, file.substring(namespace.length + 1)) ?: continue
            output.accept(id, IoSupplier { ByteArrayInputStream(bytes) })
        }
    }

    override fun getNamespaces(type: PackType): Set<String> = if (type == PackType.CLIENT_RESOURCES) namespaces else emptySet()

    override fun <T : Any> getMetadataSection(type: MetadataSectionType<T>): T? = null

    override fun close() {}

    companion object {
        val LOCATION: PackLocationInfo = PackLocationInfo(
            KilnPackSource.PACK_ID,
            Component.literal("Server-Erweiterungen (Kiln)"),
            PackSource.SERVER,
            Optional.empty<KnownPack>(),
        )
    }
}
