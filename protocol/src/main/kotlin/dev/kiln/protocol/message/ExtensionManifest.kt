package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_MANIFEST_EXTENSIONS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Configuration phase, after [Welcome]: every extension package of the server, in load order (ids unique; every
 * [ExtensionInfo.dependencies] entry appears earlier in the list).
 * May be empty; the client still answers with [ExtensionRequest] and [ExtensionsReady].
 */
public data class ExtensionManifest(val extensions: List<ExtensionInfo>) : ClientboundMessage {
    /** Wire codec: the packages in load order (ids unique, dependencies listed before their dependents). */
    public companion object : Codec<ExtensionManifest> {
        private val extensions = ExtensionInfo.list(MAX_MANIFEST_EXTENSIONS, "manifest extensions")
            .validated(::checkLoadOrder)

        override fun encode(writer: ProtocolWriter, value: ExtensionManifest): Unit =
            extensions.encode(writer, value.extensions)

        override fun decode(reader: ProtocolReader): ExtensionManifest = ExtensionManifest(extensions.decode(reader))

        private fun checkLoadOrder(extensions: List<ExtensionInfo>) {
            val listed = HashSet<String>()
            for (info in extensions) {
                if (!listed.add(info.id)) throw ProtocolException("Duplicate extension '${info.id}' in manifest")
                for (dependency in info.dependencies) {
                    if (dependency == info.id) throw ProtocolException("Extension '${info.id}' depends on itself")
                    if (dependency !in listed) {
                        throw ProtocolException("Dependency '$dependency' of '${info.id}' is not listed before it in the manifest")
                    }
                }
            }
        }
    }
}
