package dev.kiln.protocol.codec

import dev.kiln.protocol.ClickModifiers
import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.CloseScreen
import dev.kiln.protocol.DeclareKeybinds
import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ExtensionManifest
import dev.kiln.protocol.ExtensionMessage
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.Hello
import dev.kiln.protocol.Ids
import dev.kiln.protocol.KeyPressed
import dev.kiln.protocol.KeyReleased
import dev.kiln.protocol.KeybindDeclaration
import dev.kiln.protocol.ModalDismiss
import dev.kiln.protocol.OpenScreen
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_CLIENTBOUND_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSIONS
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_CHUNKS
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_ERROR_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_ICON_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_JSON_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_DEPENDENCIES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_NAME_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_EXTENSION_VERSION_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_MANIFEST_EXTENSIONS
import dev.kiln.protocol.ProtocolLimits.SHA256_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_KEYBINDS
import dev.kiln.protocol.ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_MOUSE_BUTTON
import dev.kiln.protocol.ProtocolLimits.MAX_SIZE
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_TOAST_MILLIS
import dev.kiln.protocol.ProtocolLimits.MAX_SERVERBOUND_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_TEXT_FIELD_LENGTH
import dev.kiln.protocol.ScreenClick
import dev.kiln.protocol.ScreenClosed
import dev.kiln.protocol.ScreenInput
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.ShowToast
import dev.kiln.protocol.UpdateScreen
import dev.kiln.protocol.Viewport
import dev.kiln.protocol.Welcome
import java.io.IOException

/**
 * Encodes/decodes complete channel payloads: `varint typeId` followed by the message fields.
 * Type ids are per direction. Every failure (malformed, truncated, oversized, trailing bytes, invalid values)
 * is reported as [ProtocolException]; nothing else escapes.
 */
public object MessageCodec {
    // client -> server
    private const val HELLO = 0
    private const val SCREEN_CLICK = 1
    private const val SCREEN_INPUT = 2
    private const val SCREEN_CLOSED = 3
    private const val KEY_PRESSED = 4
    private const val KEY_RELEASED = 5
    private const val SB_EXTENSION = 6
    private const val VIEWPORT = 7
    private const val MODAL_DISMISS = 8
    private const val EXTENSION_REQUEST = 9
    private const val EXTENSIONS_READY = 10

    // server -> client
    private const val WELCOME = 0
    private const val DECLARE_KEYBINDS = 1
    private const val OPEN_SCREEN = 2
    private const val UPDATE_SCREEN = 3
    private const val CLOSE_SCREEN = 4
    private const val CB_EXTENSION = 5
    private const val SHOW_TOAST = 6
    private const val EXTENSION_MANIFEST = 7
    private const val EXTENSION_CHUNK = 8

    // ---- client -> server -----------------------------------------------------------------------------------

    public fun encodeServerbound(message: ServerboundMessage): ByteArray = encode(MAX_SERVERBOUND_BYTES) { w ->
        when (message) {
            is Hello -> {
                w.varInt(HELLO)
                w.varInt(message.protocolVersion)
                w.list(message.extensions, MAX_EXTENSIONS, "extensions") { writeExtensionId(w, it) }
            }
            is ScreenClick -> {
                w.varInt(SCREEN_CLICK)
                writeScreenId(w, message.screenId)
                writeElementId(w, message.elementId)
                w.varInt(message.button, 0, MAX_MOUSE_BUTTON, "mouse button")
                w.varInt(message.modifiers, 0, ClickModifiers.ALL, "click modifiers")
            }
            is ScreenInput -> {
                w.varInt(SCREEN_INPUT)
                writeScreenId(w, message.screenId)
                writeElementId(w, message.elementId)
                w.string(message.value, MAX_TEXT_FIELD_LENGTH, "input value")
            }
            is ScreenClosed -> {
                w.varInt(SCREEN_CLOSED)
                writeScreenId(w, message.screenId)
            }
            is KeyPressed -> {
                w.varInt(KEY_PRESSED)
                writeKeybindId(w, message.keyId)
            }
            is KeyReleased -> {
                w.varInt(KEY_RELEASED)
                writeKeybindId(w, message.keyId)
            }
            is ExtensionMessage -> {
                w.varInt(SB_EXTENSION)
                writeExtension(w, message, MAX_EXTENSION_JSON_LENGTH)
            }
            is ExtensionRequest -> {
                w.varInt(EXTENSION_REQUEST)
                val seen = HashSet<String>()
                w.list(message.ids, MAX_MANIFEST_EXTENSIONS, "requested extensions") {
                    if (!seen.add(it)) throw ProtocolException("Duplicate requested extension '$it'")
                    writeLuaExtensionId(w, it)
                }
            }
            is ExtensionsReady -> {
                w.varInt(EXTENSIONS_READY)
                w.boolean(message.ok)
                w.nullableString(message.error, MAX_EXTENSION_ERROR_LENGTH, "extension error")
            }
            is Viewport -> {
                w.varInt(VIEWPORT)
                w.varInt(message.width, 1, MAX_SIZE, "viewport width")
                w.varInt(message.height, 1, MAX_SIZE, "viewport height")
            }
            is ModalDismiss -> {
                w.varInt(MODAL_DISMISS)
                writeScreenId(w, message.screenId)
                writeElementId(w, message.modalId)
            }
        }
    }

    public fun decodeServerbound(bytes: ByteArray): ServerboundMessage = decode(bytes, MAX_SERVERBOUND_BYTES) { r ->
        when (val type = r.varInt()) {
            HELLO -> Hello(
                protocolVersion = r.varInt(),
                extensions = r.list(MAX_EXTENSIONS, "extensions") { readExtensionId(r) },
            )
            SCREEN_CLICK -> ScreenClick(
                readScreenId(r), readElementId(r),
                r.varInt(0, MAX_MOUSE_BUTTON, "mouse button"),
                r.varInt(0, ClickModifiers.ALL, "click modifiers"),
            )
            SCREEN_INPUT -> ScreenInput(readScreenId(r), readElementId(r), r.string(MAX_TEXT_FIELD_LENGTH, "input value"))
            SCREEN_CLOSED -> ScreenClosed(readScreenId(r))
            KEY_PRESSED -> KeyPressed(readKeybindId(r))
            KEY_RELEASED -> KeyReleased(readKeybindId(r))
            SB_EXTENSION -> readExtension(r, MAX_EXTENSION_JSON_LENGTH)
            VIEWPORT -> Viewport(r.varInt(1, MAX_SIZE, "viewport width"), r.varInt(1, MAX_SIZE, "viewport height"))
            MODAL_DISMISS -> ModalDismiss(readScreenId(r), readElementId(r))
            EXTENSION_REQUEST -> {
                val seen = HashSet<String>()
                ExtensionRequest(r.list(MAX_MANIFEST_EXTENSIONS, "requested extensions") {
                    readLuaExtensionId(r).also { if (!seen.add(it)) throw ProtocolException("Duplicate requested extension '$it'") }
                })
            }
            EXTENSIONS_READY -> ExtensionsReady(r.boolean(), r.nullableString(MAX_EXTENSION_ERROR_LENGTH, "extension error"))
            else -> throw ProtocolException("Unknown serverbound message type $type")
        }
    }

    // ---- server -> client -----------------------------------------------------------------------------------

    public fun encodeClientbound(message: ClientboundMessage): ByteArray = encode(MAX_CLIENTBOUND_BYTES) { w ->
        when (message) {
            is Welcome -> {
                w.varInt(WELCOME)
                w.varInt(message.protocolVersion)
            }
            is DeclareKeybinds -> {
                w.varInt(DECLARE_KEYBINDS)
                val seen = HashSet<String>()
                w.list(message.keybinds, MAX_KEYBINDS, "keybinds") {
                    if (!seen.add(it.id)) throw ProtocolException("Duplicate keybind id '${it.id}'")
                    writeKeybindId(w, it.id)
                    w.string(it.name, MAX_KEYBIND_TEXT_LENGTH, "keybind name")
                    w.string(it.category, MAX_KEYBIND_TEXT_LENGTH, "keybind category")
                    w.string(it.defaultKey, MAX_ID_LENGTH, "default key")
                }
            }
            is OpenScreen -> {
                w.varInt(OPEN_SCREEN)
                writeScreenId(w, message.screenId)
                w.boolean(message.fullscreen)
                ElementCodec.encode(w, message.root)
            }
            is UpdateScreen -> {
                w.varInt(UPDATE_SCREEN)
                writeScreenId(w, message.screenId)
                w.boolean(message.fullscreen)
                ElementCodec.encode(w, message.root)
            }
            is CloseScreen -> {
                w.varInt(CLOSE_SCREEN)
                writeScreenId(w, message.screenId)
            }
            is ExtensionMessage -> {
                w.varInt(CB_EXTENSION)
                writeExtension(w, message, MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH)
            }
            is ExtensionManifest -> {
                w.varInt(EXTENSION_MANIFEST)
                val seen = HashSet<String>()
                w.list(message.extensions, MAX_MANIFEST_EXTENSIONS, "manifest extensions") {
                    if (!seen.add(it.id)) throw ProtocolException("Duplicate extension '${it.id}' in manifest")
                    checkDependencyOrder(it, seen)
                    writeExtensionInfo(w, it)
                }
            }
            is ExtensionChunk -> {
                w.varInt(EXTENSION_CHUNK)
                writeLuaExtensionId(w, message.id)
                w.varInt(message.total, 1, MAX_EXTENSION_CHUNKS, "chunk total")
                w.varInt(message.index, 0, message.total - 1, "chunk index")
                if (message.data.isEmpty()) throw ProtocolException("Empty extension chunk")
                w.byteArray(message.data, MAX_EXTENSION_CHUNK_BYTES, "extension chunk")
            }
            is ShowToast -> {
                w.varInt(SHOW_TOAST)
                w.string(message.title, MAX_STRING_LENGTH, "toast title")
                w.nullableString(message.text, MAX_STRING_LENGTH, "toast text")
                ElementCodec.writeNullableItem(w, message.icon)
                w.varInt(message.displayMillis, 1, MAX_TOAST_MILLIS, "toast display time")
            }
        }
    }

    public fun decodeClientbound(bytes: ByteArray): ClientboundMessage = decode(bytes, MAX_CLIENTBOUND_BYTES) { r ->
        when (val type = r.varInt()) {
            WELCOME -> Welcome(r.varInt())
            DECLARE_KEYBINDS -> {
                val seen = HashSet<String>()
                DeclareKeybinds(r.list(MAX_KEYBINDS, "keybinds") {
                    val id = readKeybindId(r)
                    if (!seen.add(id)) throw ProtocolException("Duplicate keybind id '$id'")
                    KeybindDeclaration(
                        id = id,
                        name = r.string(MAX_KEYBIND_TEXT_LENGTH, "keybind name"),
                        category = r.string(MAX_KEYBIND_TEXT_LENGTH, "keybind category"),
                        defaultKey = r.string(MAX_ID_LENGTH, "default key"),
                    )
                })
            }
            OPEN_SCREEN -> {
                val screenId = readScreenId(r)
                val fullscreen = r.boolean()
                OpenScreen(screenId, ElementCodec.decode(r), fullscreen)
            }
            UPDATE_SCREEN -> {
                val screenId = readScreenId(r)
                val fullscreen = r.boolean()
                UpdateScreen(screenId, ElementCodec.decode(r), fullscreen)
            }
            CLOSE_SCREEN -> CloseScreen(readScreenId(r))
            CB_EXTENSION -> readExtension(r, MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH)
            EXTENSION_MANIFEST -> {
                val seen = HashSet<String>()
                ExtensionManifest(r.list(MAX_MANIFEST_EXTENSIONS, "manifest extensions") {
                    readExtensionInfo(r).also {
                        if (!seen.add(it.id)) throw ProtocolException("Duplicate extension '${it.id}' in manifest")
                        checkDependencyOrder(it, seen)
                    }
                })
            }
            EXTENSION_CHUNK -> {
                val id = readLuaExtensionId(r)
                val total = r.varInt(1, MAX_EXTENSION_CHUNKS, "chunk total")
                val index = r.varInt(0, total - 1, "chunk index")
                val data = r.byteArray(MAX_EXTENSION_CHUNK_BYTES, "extension chunk")
                if (data.isEmpty()) throw ProtocolException("Empty extension chunk")
                ExtensionChunk(id, index, total, data)
            }
            SHOW_TOAST -> ShowToast(
                title = r.string(MAX_STRING_LENGTH, "toast title"),
                text = r.nullableString(MAX_STRING_LENGTH, "toast text"),
                icon = ElementCodec.readNullableItem(r),
                displayMillis = r.varInt(1, MAX_TOAST_MILLIS, "toast display time"),
            )
            else -> throw ProtocolException("Unknown clientbound message type $type")
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private inline fun encode(maxBytes: Int, body: (ProtocolWriter) -> Unit): ByteArray {
        val writer = ProtocolWriter()
        try {
            body(writer)
        } catch (e: IOException) {
            throw ProtocolException("Encoding failed", e)
        }
        if (writer.size > maxBytes) throw ProtocolException("Message too large (${writer.size} > $maxBytes bytes)")
        return writer.toByteArray()
    }

    private inline fun <T> decode(bytes: ByteArray, maxBytes: Int, body: (ProtocolReader) -> T): T {
        if (bytes.size > maxBytes) throw ProtocolException("Message too large (${bytes.size} > $maxBytes bytes)")
        if (bytes.isEmpty()) throw ProtocolException("Empty message")
        val reader = ProtocolReader(bytes)
        val message = try {
            body(reader)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: IOException) {
            throw ProtocolException("Malformed message", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("Invalid message: ${e.message}", e)
        } catch (e: IndexOutOfBoundsException) {
            throw ProtocolException("Malformed message", e)
        }
        reader.expectEnd()
        return message
    }

    private fun writeScreenId(w: ProtocolWriter, id: Int) = w.varInt(id, 0, Int.MAX_VALUE, "screen id")
    private fun readScreenId(r: ProtocolReader): Int = r.varInt(0, Int.MAX_VALUE, "screen id")

    private fun writeElementId(w: ProtocolWriter, id: String) {
        if (!Ids.isValidElementId(id)) throw ProtocolException("Invalid element id '$id'")
        w.string(id, MAX_ID_LENGTH, "element id")
    }

    private fun readElementId(r: ProtocolReader): String =
        r.string(MAX_ID_LENGTH, "element id").also { if (!Ids.isValidElementId(it)) throw ProtocolException("Invalid element id '$it'") }

    private fun writeKeybindId(w: ProtocolWriter, id: String) {
        if (!Ids.isValidKeybindId(id)) throw ProtocolException("Invalid keybind id '$id'")
        w.string(id, MAX_ID_LENGTH, "keybind id")
    }

    private fun readKeybindId(r: ProtocolReader): String =
        r.string(MAX_ID_LENGTH, "keybind id").also { if (!Ids.isValidKeybindId(it)) throw ProtocolException("Invalid keybind id '$it'") }

    private fun writeExtensionId(w: ProtocolWriter, id: String) {
        if (!Ids.isValidExtensionId(id)) throw ProtocolException("Invalid extension id '$id'")
        w.string(id, MAX_ID_LENGTH, "extension id")
    }

    private fun readExtensionId(r: ProtocolReader): String =
        r.string(MAX_ID_LENGTH, "extension id").also { if (!Ids.isValidExtensionId(it)) throw ProtocolException("Invalid extension id '$it'") }

    private fun writeLuaExtensionId(w: ProtocolWriter, id: String) {
        if (!Ids.isValidLuaExtensionId(id)) throw ProtocolException("Invalid extension id '$id'")
        w.string(id, MAX_ID_LENGTH, "extension id")
    }

    private fun readLuaExtensionId(r: ProtocolReader): String =
        r.string(MAX_ID_LENGTH, "extension id").also { if (!Ids.isValidLuaExtensionId(it)) throw ProtocolException("Invalid extension id '$it'") }

    private fun validateChannel(channel: ResourceId) {
        if (!Ids.isValidLuaExtensionId(channel.namespace) || !Ids.isValidExtensionName(channel.path)) {
            throw ProtocolException("Invalid extension channel '$channel' (expected <extension id>:<name>)")
        }
    }

    private fun writeExtension(w: ProtocolWriter, message: ExtensionMessage, maxJson: Int) {
        validateChannel(message.channel)
        w.resourceId(message.channel)
        w.string(message.json, maxJson, "extension message json")
    }

    private fun readExtension(r: ProtocolReader, maxJson: Int): ExtensionMessage {
        val channel = r.resourceId("extension channel")
        validateChannel(channel)
        return ExtensionMessage(channel, r.string(maxJson, "extension message json"))
    }

    /** Every dependency of [info] must already be in [seen] (listed earlier); [seen] already contains [info.id]. */
    private fun checkDependencyOrder(info: ExtensionInfo, seen: Set<String>) {
        for (dependency in info.dependencies) {
            if (dependency == info.id) throw ProtocolException("Extension '${info.id}' depends on itself")
            if (dependency !in seen) throw ProtocolException("Dependency '$dependency' of '${info.id}' is not listed before it in the manifest")
        }
    }

    private fun validateDependencies(info: String, dependencies: List<String>) {
        if (dependencies.toSet().size != dependencies.size) throw ProtocolException("Duplicate dependency of '$info' in $dependencies")
        dependencies.firstOrNull { !Ids.isValidLuaExtensionId(it) }?.let { throw ProtocolException("Invalid dependency id '$it'") }
    }

    private fun validatePermissions(permissions: List<String>) {
        if (permissions.toSet().size != permissions.size) throw ProtocolException("Duplicate permission in $permissions")
        permissions.firstOrNull { !ExtensionPermissions.isValid(it) }?.let { throw ProtocolException("Unknown permission '$it'") }
    }

    private fun writeExtensionInfo(w: ProtocolWriter, info: ExtensionInfo) {
        writeLuaExtensionId(w, info.id)
        w.string(info.version, MAX_EXTENSION_VERSION_LENGTH, "extension version")
        w.string(info.name, MAX_EXTENSION_NAME_LENGTH, "extension name")
        if (info.sha256.size != SHA256_BYTES) throw ProtocolException("sha256 must have $SHA256_BYTES bytes, has ${info.sha256.size}")
        w.byteArray(info.sha256, SHA256_BYTES, "sha256")
        w.varInt(info.size, 1, MAX_EXTENSION_PACKAGE_BYTES, "extension package size")
        validatePermissions(info.permissions)
        w.list(info.permissions, ExtensionPermissions.ALL.size, "permissions") { w.string(it, MAX_ID_LENGTH, "permission") }
        val icon = info.icon
        w.boolean(icon != null)
        if (icon != null) w.byteArray(icon, MAX_EXTENSION_ICON_BYTES, "extension icon")
        validateDependencies(info.id, info.dependencies)
        w.list(info.dependencies, MAX_EXTENSION_DEPENDENCIES, "dependencies") { writeLuaExtensionId(w, it) }
    }

    private fun readExtensionInfo(r: ProtocolReader): ExtensionInfo {
        val id = readLuaExtensionId(r)
        val version = r.string(MAX_EXTENSION_VERSION_LENGTH, "extension version")
        val name = r.string(MAX_EXTENSION_NAME_LENGTH, "extension name")
        val sha256 = r.byteArray(SHA256_BYTES, "sha256")
        if (sha256.size != SHA256_BYTES) throw ProtocolException("sha256 must have $SHA256_BYTES bytes, has ${sha256.size}")
        val size = r.varInt(1, MAX_EXTENSION_PACKAGE_BYTES, "extension package size")
        val permissions = r.list(ExtensionPermissions.ALL.size, "permissions") { r.string(MAX_ID_LENGTH, "permission") }
        validatePermissions(permissions)
        val icon = if (r.boolean()) r.byteArray(MAX_EXTENSION_ICON_BYTES, "extension icon") else null
        val dependencies = r.list(MAX_EXTENSION_DEPENDENCIES, "dependencies") { readLuaExtensionId(r) }
        validateDependencies(id, dependencies)
        return ExtensionInfo(id, version, name, sha256, size, permissions, icon, dependencies)
    }
}
