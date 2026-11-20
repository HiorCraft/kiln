package dev.kiln.server.runtime.extension

import dev.kiln.protocol.AssetRules
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.ExtensionPermissions

/** A validated, packed extension: what is announced in the manifest and streamed to clients. */
class ExtensionPackage(
    val id: String,
    val version: String,
    val name: String,
    /** Lua entry point, `null` for a resource-only package. */
    val main: String?,
    /** Canonical order ([ExtensionPermissions.ALL]). */
    val permissions: List<String>,
    /** Deterministic zip, files at the root. */
    val zip: ByteArray,
    val sha256: ByteArray,
    /** Paths inside the zip, sorted. */
    val files: List<String>,
    /** Optional consent-screen icon (PNG), sent in the manifest. */
    val icon: ByteArray? = null,
    /** `extension.json` `dependencies` (ids + optional version ranges), in declaration order. */
    val dependencies: List<ExtensionDependency> = emptyList(),
    /** Sum of all file sizes (what the client holds in memory). */
    val unpackedSize: Long = 0,
) {
    val sha256Hex: String get() = info.sha256Hex

    /** The package carries a resource-pack tree (`assets/<id>/…`). */
    val hasAssets: Boolean get() = files.any { it.startsWith(AssetRules.ROOT) }

    val info: ExtensionInfo
        get() = ExtensionInfo(id, version, name, sha256.copyOf(), zip.size, permissions, icon?.copyOf(), dependencies.map { it.id })
}
