package dev.kiln.client.resource

import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackMetadataResources
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackCompatibility
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.world.flag.FeatureFlagSet
import java.util.function.Consumer
import java.util.stream.Stream

/**
 * Offers the extension resources of the current connection as a required, fixed, topmost client pack
 * ([PACK_ID]). Added to the client's resource pack repository by `PackRepositoryMixin`; after [set] / [clear] the
 * caller reloads the resources (the repository then adds or drops the pack on its own, as for server packs).
 */
object KilnPackSource : RepositorySource {
    const val PACK_ID: String = "kiln:server"

    @Volatile
    private var files: Map<String, ByteArray> = emptyMap()

    /** Resources of the loaded extensions (paths relative to `assets/`). */
    fun set(files: Map<String, ByteArray>) {
        this.files = files.toMap()
    }

    /** Removes the pack; `true` if there was one (a resource reload is due). */
    fun clear(): Boolean {
        val had = files.isNotEmpty()
        files = emptyMap()
        return had
    }

    override fun loadPacks(consumer: Consumer<Pack>) {
        val current = files
        if (current.isEmpty()) return
        val supplier = object : Pack.ResourcesSupplier {
            override fun openMetadata(location: PackLocationInfo): PackMetadataResources = KilnPackResources(current)
            override fun openResources(location: PackLocationInfo, metadata: Pack.Metadata): Stream<PackResources> = Stream.of(KilnPackResources(current))
        }
        val metadata = Pack.Metadata(
            Component.literal("Modelle, Texturen, Sounds und Schriften des Servers"),
            PackCompatibility.COMPATIBLE,
            FeatureFlagSet.of(),
            emptyList(),
        )
        consumer.accept(Pack(KilnPackResources.LOCATION, supplier, metadata, PackSelectionConfig(true, Pack.Position.TOP, true)))
    }
}
