package dev.kiln.protocol

/** Any kiln message. */
public sealed interface Message

/** Client -> server. */
public sealed interface ServerboundMessage : Message

/** Server -> client. */
public sealed interface ClientboundMessage : Message

// ---- client -> server ---------------------------------------------------------------------------------------

/**
 * First client message, sent in the **configuration phase** after announcing [CHANNEL] via `minecraft:register`.
 * Its layout never changes between protocol versions so that a mismatch can always be detected; everything else
 * the client reports goes into separate messages (e.g. [Viewport]).
 * [extensions]: legacy field, empty since v3 (the server ignores it).
 */
public data class Hello(val protocolVersion: Int, val extensions: List<String>) : ServerboundMessage

/**
 * The client's GUI-scaled window size (`Window.getGuiScaledWidth/Height`). Sent right after [Hello] and whenever it
 * changes (window resize, GUI scale change, fullscreen toggle). The server re-renders the open screen on change.
 */
public data class Viewport(val width: Int, val height: Int) : ServerboundMessage

/** Bit flags of [ScreenClick.modifiers]. */
public object ClickModifiers {
    public const val SHIFT: Int = 1
    public const val CONTROL: Int = 2
    public const val ALT: Int = 4
    public const val ALL: Int = SHIFT or CONTROL or ALT
}

/**
 * Click on a [Button], [Custom], [ItemSlot] or clickable [Panel].
 * [button]: 0 = left, 1 = right, 2 = middle (GLFW mouse button numbers); keyboard activation counts as left.
 * [modifiers]: [ClickModifiers] held during the click.
 */
public data class ScreenClick(val screenId: Int, val elementId: String, val button: Int, val modifiers: Int = 0) :
    ServerboundMessage {
    val shift: Boolean get() = modifiers and ClickModifiers.SHIFT != 0
}

/**
 * New value of an input element:
 * - [TextField]: its content (throttled while typing, immediately on enter); numeric fields only valid numbers or "".
 * - [Dropdown]: the chosen [DropdownOption.value].
 * - [Checkbox]: `"true"` / `"false"`.
 * - [Slider]: the new value as decimal number.
 */
public data class ScreenInput(val screenId: Int, val elementId: String, val value: String) : ServerboundMessage

/** Escape was pressed while [modalId] (the topmost [Modal]) was open; the screen stays open. */
public data class ModalDismiss(val screenId: Int, val modalId: String) : ServerboundMessage

/** The screen was closed by the player, or a server-side [CloseScreen] is acknowledged. */
public data class ScreenClosed(val screenId: Int) : ServerboundMessage

public data class KeyPressed(val keyId: String) : ServerboundMessage

public data class KeyReleased(val keyId: String) : ServerboundMessage

/**
 * Configuration phase, answer to [ExtensionManifest] (exactly once): the [ids] whose package the client does not
 * have in its cache (by sha256) and wants streamed as [ExtensionChunk]s. May be empty. Every id must be listed in
 * the manifest, no duplicates.
 */
public data class ExtensionRequest(val ids: List<String>) : ServerboundMessage

/**
 * Configuration phase, last client message: every manifest package is verified, consented to and loaded ([ok]),
 * or not ([ok] = false, [error] = human-readable reason, shown in the disconnect screen). The server lets the
 * player enter the world only after `ok = true`; otherwise it disconnects the player.
 */
public data class ExtensionsReady(val ok: Boolean, val error: String? = null) : ServerboundMessage

// ---- server -> client ---------------------------------------------------------------------------------------

/** Configuration phase: answer to [Hello]; also sent on a version mismatch right before the disconnect. */
public data class Welcome(val protocolVersion: Int) : ClientboundMessage

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
            (icon?.contentEquals(other.icon) ?: (other.icon == null)) && dependencies == other.dependencies)

    override fun hashCode(): Int = listOf(id, version, name, sha256.contentHashCode(), size, permissions, icon?.contentHashCode(), dependencies).hashCode()

    override fun toString(): String = "ExtensionInfo($id $version '$name', sha256=$sha256Hex, $size bytes, $permissions, deps=$dependencies)"
}

/**
 * Configuration phase, after [Welcome]: every extension package of the server, in load order (ids unique; every
 * [ExtensionInfo.dependencies] entry appears earlier in the list).
 * May be empty; the client still answers with [ExtensionRequest] and [ExtensionsReady].
 */
public data class ExtensionManifest(val extensions: List<ExtensionInfo>) : ClientboundMessage

/**
 * Configuration phase: part [index] (0-based) of [total] of package [id]. Chunks of one package arrive in order;
 * every chunk but the last carries exactly [ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES] bytes, the last 1..that
 * many. [total] equals [ExtensionInfo.chunkCount]; the concatenation is the zip with the manifest's size and sha256.
 */
public class ExtensionChunk(public val id: String, public val index: Int, public val total: Int, public val data: ByteArray) :
    ClientboundMessage {

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExtensionChunk && id == other.id && index == other.index && total == other.total &&
            data.contentEquals(other.data))

    override fun hashCode(): Int = ((id.hashCode() * 31 + index) * 31 + total) * 31 + data.contentHashCode()

    override fun toString(): String = "ExtensionChunk($id ${index + 1}/$total, ${data.size} bytes)"
}

/**
 * A server-defined keybind. [name] and [category] are display strings (also usable as translation keys),
 * [defaultKey] is an `InputConstants` key name such as `key.keyboard.g` or `key.mouse.middle`
 * (`key.keyboard.unknown` = unbound).
 */
public data class KeybindDeclaration(val id: String, val name: String, val category: String, val defaultKey: String)

/** Replaces the complete set of server keybinds. */
public data class DeclareKeybinds(val keybinds: List<KeybindDeclaration>) : ClientboundMessage

/**
 * Opens a screen; an already open kiln screen is replaced.
 * [fullscreen]: the root was rendered for the client's [Viewport]; the client places it at its own x/y (no
 * centering) and, until the next update arrives after a resize, stretches the root's background to the window.
 */
public data class OpenScreen(val screenId: Int, val root: Element, val fullscreen: Boolean = false) : ClientboundMessage

/** Replaces the tree of an open screen; client state is kept for elements whose id still exists. */
public data class UpdateScreen(val screenId: Int, val root: Element, val fullscreen: Boolean = false) : ClientboundMessage

public data class CloseScreen(val screenId: Int) : ClientboundMessage

/**
 * Shows a notification toast (top right, vanilla toast stack), independent of any screen.
 * [title] / [text]: component JSON; [icon]: optional item drawn at the left.
 */
public data class ShowToast(
    val title: String,
    val text: String? = null,
    val icon: ItemStackData? = null,
    val displayMillis: Int = 5_000,
) : ClientboundMessage

// ---- both directions ----------------------------------------------------------------------------------------

/**
 * Play phase: a message on channel `<extension id>:<name>` ([channel]) between a Lua extension
 * (`kiln.net.channel(name)`) and a server plugin (`Extension.channel(name)`). [json] is one JSON value as
 * text (UTF-8 on the wire, at most [ProtocolLimits.MAX_EXTENSION_JSON_LENGTH] chars). The codec checks only the
 * channel and the length; both ends parse/validate it (nesting depth <= 16) and the server rate-limits it.
 */
public data class ExtensionMessage(val channel: ResourceId, val json: String) : ServerboundMessage, ClientboundMessage

/**
 * Play phase (v6): switches the client's post-processing effect to [id] (`<namespace>:<path>`, a
 * `post_effect/<path>.json` of a loaded extension's resources), or turns it off ([id] = `null`).
 * The client ignores unknown effects.
 */
public data class SetPostEffect(val id: String?) : ClientboundMessage

/**
 * One creative-inventory entry (v7): an own tab ([target] = `null`, at most [ProtocolLimits.MAX_CREATIVE_TABS] of
 * them, shown in the client's placeholder tabs in order) or items added to a vanilla tab ([target] = its id, e.g.
 * `minecraft:ingredients`). [id] is a `namespace:path` key, [title] a Component as JSON, items are full stacks.
 */
public data class CreativeTabData(
    val id: String,
    val title: String,
    val icon: ItemStackData?,
    val items: List<ItemStackData>,
    val target: String?,
)

/**
 * Play phase (v7): creative-inventory content. [reset] = `true` replaces everything the client had (first message
 * of an update); further messages of the same update append [tabs] (the server splits large updates).
 */
public data class CreativeTabs(val reset: Boolean, val tabs: List<CreativeTabData>) : ClientboundMessage

/** Play phase (v7): traits of entity [entityId] (sent when it is tracked): [solid] = collide like a boat. */
public data class EntityTraits(val entityId: Int, val solid: Boolean) : ClientboundMessage

/** Play phase (v7): the entities stopped being tracked; their traits are dropped. */
public data class EntityTraitsRemove(val entityIds: List<Int>) : ClientboundMessage

/**
 * Play phase (v8): entity [entityId] is drawn with the Kiln bone model [model] (`namespace:name`, files
 * `assets/<ns>/kiln/entity/<name>.geo.json` / `.animation.json`, texture `textures/entity/<name>.png`) and has a
 * [width] × [height] hit box; [headBone] = the bone `head` follows the look direction. Sent when it is tracked.
 */
public data class EntityAppearance(val entityId: Int, val model: String, val width: Float, val height: Float, val headBone: Boolean) :
    ClientboundMessage

/** How a [PlayAnimation] plays. */
public object AnimationMode {
    /** Once, then back to the state animation. */
    public const val ONCE: Int = 0

    /** Repeats until stopped. */
    public const val LOOP: Int = 1

    /** Once, then holds the last frame until stopped. */
    public const val HOLD: Int = 2
}

/** Play phase (v8): plays animation [name] of the entity's model ([AnimationMode], [speed] 0..8). */
public data class PlayAnimation(val entityId: Int, val name: String, val mode: Int, val speed: Float) : ClientboundMessage

/** Play phase (v8): stops animation [name] (`null` = every server animation) of the entity. */
public data class StopAnimation(val entityId: Int, val name: String?) : ClientboundMessage

