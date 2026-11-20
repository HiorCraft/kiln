package dev.kiln.protocol.message

import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_DEPENDENCIES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_ICON_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_NAME_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_VERSION_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH
import dev.kiln.protocol.ProtocolLimits.SHA256_BYTES
import dev.kiln.protocol.codec.ByteArrayCodec
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.StringCodec
import dev.kiln.protocol.codec.requireDistinct

/**
 * One Lua extension package as announced in [ExtensionManifest].
 * - [id]: [Ids.isValidLuaExtensionId]; namespace of its widgets, channels and textures.
 * - [sha256]: SHA-256 (32 bytes) of the package zip; the client cache key.
 * - [size]: zip size in bytes, 1..[ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES].
 * - [permissions]: subset of [ExtensionPermissions.ALL], no duplicates.
 * - [icon]: optional PNG (at most [ProtocolLimits.MAX_EXTENSION_ICON_BYTES], 64x64 px) for the consent screen, so the
 *   client can ask for consent before anything is downloaded.
 * - [dependencies]: ids of the extensions this one imports (`extension.json` `dependencies`, version ranges are
 *   checked by the server only); at most [ProtocolLimits.MAX_EXTENSION_DEPENDENCIES], no duplicates, not the own id.
 *   In an [ExtensionManifest] every dependency is listed **before** the extension (load order), which also rules out
 *   cycles and missing dependencies.
 */
public class ExtensionInfo(
    public val id: String,
    public val version: String,
    public val name: String,
    public val sha256: ByteArray,
    public val size: Int,
    public val permissions: List<String>,
    public val icon: ByteArray? = null,
    public val dependencies: List<String> = emptyList(),
) {
    /** [sha256] as 64 lowercase hex chars (cache file name). */
    public val sha256Hex: String get() = sha256.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Number of [ExtensionChunk]s the package is streamed in. */
    public val chunkCount: Int
        get() = (size + ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES - 1) / ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExtensionInfo && id == other.id && version == other.version && name == other.name &&
            sha256.contentEquals(other.sha256) && size == other.size && permissions == other.permissions &&
            icon.contentEquals(other.icon) && dependencies == other.dependencies)

    override fun hashCode(): Int =
        listOf(id, version, name, sha256.contentHashCode(), size, permissions, icon.contentHashCode(), dependencies).hashCode()

    override fun toString(): String =
        "ExtensionInfo($id $version '$name', sha256=$sha256Hex, $size bytes, $permissions, deps=$dependencies)"

    /** Wire codec; permissions are known and distinct, dependencies distinct. */
    public companion object : Codec<ExtensionInfo> {
        private val version = StringCodec(MAX_EXTENSION_VERSION_LENGTH, "extension version")
        private val name = StringCodec(MAX_EXTENSION_NAME_LENGTH, "extension name")
        private val sha256 = ByteArrayCodec(SHA256_BYTES, "sha256").validated(::checkSha256)
        private val size = RangedVarIntCodec(1, MAX_EXTENSION_PACKAGE_BYTES, "extension package size")
        private val permissions = StringCodec(MAX_ID_LENGTH, "permission").validated(::checkPermission)
            .list(ExtensionPermissions.ALL.size, "permissions")
            .validated { permissions -> requireDistinct(permissions) { "Duplicate permission '$it'" } }
        private val icon = ByteArrayCodec(MAX_EXTENSION_ICON_BYTES, "extension icon").nullable()
        private val dependencies = IdCodec.LUA_EXTENSION.list(MAX_EXTENSION_DEPENDENCIES, "dependencies")
            .validated { dependencies -> requireDistinct(dependencies) { "Duplicate dependency '$it'" } }

        override fun encode(writer: ProtocolWriter, value: ExtensionInfo) {
            IdCodec.LUA_EXTENSION.encode(writer, value.id)
            version.encode(writer, value.version)
            name.encode(writer, value.name)
            sha256.encode(writer, value.sha256)
            size.encode(writer, value.size)
            permissions.encode(writer, value.permissions)
            icon.encode(writer, value.icon)
            dependencies.encode(writer, value.dependencies)
        }

        override fun decode(reader: ProtocolReader): ExtensionInfo = ExtensionInfo(
            id = IdCodec.LUA_EXTENSION.decode(reader),
            version = version.decode(reader),
            name = name.decode(reader),
            sha256 = sha256.decode(reader),
            size = size.decode(reader),
            permissions = permissions.decode(reader),
            icon = icon.decode(reader),
            dependencies = dependencies.decode(reader),
        )

        private fun checkSha256(sha256: ByteArray) {
            if (sha256.size != SHA256_BYTES) {
                throw ProtocolException("sha256 must have $SHA256_BYTES bytes, has ${sha256.size}")
            }
        }

        private fun checkPermission(permission: String) {
            if (!ExtensionPermissions.isValid(permission)) throw ProtocolException("Unknown permission '$permission'")
        }
    }
}
