package dev.kiln.protocol

/**
 * Version of the wire format. Client and server compare it during the handshake ([Hello] / [Welcome]) in the
 * configuration phase; on a mismatch the server disconnects the player (see `docs/lua-extensions.md`).
 *
 * v3: Lua extension packages delivered in the configuration phase ([ExtensionManifest], [ExtensionRequest],
 * [ExtensionChunk], [ExtensionsReady]); [ExtensionMessage] carries JSON text instead of raw bytes.
 * v4: [ExtensionInfo.icon] in the manifest; the client asks for consent before it sends [ExtensionRequest], so
 * nothing is downloaded without consent.
 * v5: [ExtensionInfo.dependencies] (manifest in dependency order), permissions `screens` and `inventory`, larger
 * clientbound [ExtensionMessage]s ([ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH]).
 * v6: resource-pack trees in packages (`assets/<id>/…`, [AssetRules]), permission `resources`, larger packages
 * ([ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES]), [SetPostEffect].
 * v7: [CreativeTabs], [EntityTraits], [EntityTraitsRemove] (furniture).
 * v8: [EntityAppearance], [PlayAnimation], [StopAnimation] (custom entities).
 */
public const val PROTOCOL_VERSION: Int = 8

/** The single plugin-messaging / custom-payload channel used in both directions. */
public const val CHANNEL: String = "kiln:main"

/** Namespace of [CHANNEL]. */
public const val CHANNEL_NAMESPACE: String = "kiln"

/** Path of [CHANNEL]. */
public const val CHANNEL_PATH: String = "main"

/**
 * Hard limits enforced by the codec on encode **and** decode. Anything above a limit is rejected with a
 * [ProtocolException]; it never reaches game code.
 */
public object ProtocolLimits {
    /** Maximum size of a whole client -> server message (vanilla serverbound custom payload limit). */
    public const val MAX_SERVERBOUND_BYTES: Int = 32_767

    /** Maximum size of a whole server -> client message (vanilla clientbound custom payload limit). */
    public const val MAX_CLIENTBOUND_BYTES: Int = 1_048_576

    /** Maximum number of elements in one GUI tree (root included). */
    public const val MAX_ELEMENTS: Int = 1_024

    /** Maximum nesting depth of a GUI tree; the root has depth 1. */
    public const val MAX_DEPTH: Int = 16

    /** Maximum length (UTF-16 chars) of free-form strings, e.g. component JSON. */
    public const val MAX_STRING_LENGTH: Int = 32_767

    /** Maximum length of identifiers: element ids, keybind ids, extension ids, property keys, key names. */
    public const val MAX_ID_LENGTH: Int = 64

    /** Maximum length of a `namespace:path` resource id. */
    public const val MAX_RESOURCE_ID_LENGTH: Int = 256

    /** Maximum length of a keybind name or category. */
    public const val MAX_KEYBIND_TEXT_LENGTH: Int = 256

    /** Maximum length of a property value of a [Custom] element. */
    public const val MAX_PROPERTY_VALUE_LENGTH: Int = 8_192

    /** Maximum number of properties of a [Custom] element. */
    public const val MAX_PROPERTIES: Int = 64

    /** Maximum `maxLength` of a [TextField] and length of a [ScreenInput] value. */
    public const val MAX_TEXT_FIELD_LENGTH: Int = 1_024

    /** Maximum number of client extensions announced in [Hello] (always empty since v3). */
    public const val MAX_EXTENSIONS: Int = 128

    /** Maximum number of Lua extension packages in one [ExtensionManifest]. */
    public const val MAX_MANIFEST_EXTENSIONS: Int = 128

    /** Maximum (zipped) size of one Lua extension package, in bytes. */
    public const val MAX_EXTENSION_PACKAGE_BYTES: Int = 32 * 1024 * 1024

    /** Maximum unpacked size of one extension package (all files together), in bytes; since v6. */
    public const val MAX_EXTENSION_UNPACKED_BYTES: Int = 64 * 1024 * 1024

    /**
     * Maximum unpacked size of all extension packages of a server together (they are held in memory on the client),
     * in bytes; since v6.
     */
    public const val MAX_TOTAL_UNPACKED_BYTES: Long = 256L * 1024 * 1024

    /** Own creative tabs the client offers (placeholders `kiln:server_0..7`); since v7. */
    public const val MAX_CREATIVE_TABS: Int = 8

    /** Entries (own tabs + vanilla-tab additions) of one [CreativeTabs] update; since v7. */
    public const val MAX_CREATIVE_ENTRIES: Int = 32

    /** Items of one creative entry; since v7. */
    public const val MAX_CREATIVE_ITEMS: Int = 512

    /** Entity ids in one [EntityTraitsRemove]; since v7. */
    public const val MAX_ENTITY_IDS: Int = 1024

    /** Maximum number of files in one extension package; since v6. */
    public const val MAX_EXTENSION_FILES: Int = 4096

    /** Maximum size of one `.ogg` sound below `assets/`, in bytes. */
    public const val MAX_SOUND_BYTES: Int = 8 * 1024 * 1024

    /** Maximum size of one `.ttf`/`.otf` font below `assets/`, in bytes. */
    public const val MAX_FONT_BYTES: Int = 4 * 1024 * 1024

    /** Maximum width/height of a PNG below `assets/`, in pixels. */
    public const val MAX_ASSET_TEXTURE_SIZE: Int = 4096

    /** Maximum payload of one [ExtensionChunk], in bytes. */
    public const val MAX_EXTENSION_CHUNK_BYTES: Int = 512 * 1024

    /** Maximum number of chunks of one package (`MAX_EXTENSION_PACKAGE_BYTES / MAX_EXTENSION_CHUNK_BYTES`). */
    public const val MAX_EXTENSION_CHUNKS: Int = MAX_EXTENSION_PACKAGE_BYTES / MAX_EXTENSION_CHUNK_BYTES

    /** Length of [ExtensionInfo.sha256]. */
    public const val SHA256_BYTES: Int = 32

    /** Maximum length of [ExtensionInfo.version]. */
    public const val MAX_EXTENSION_VERSION_LENGTH: Int = 64

    /** Maximum length of [ExtensionInfo.name]. */
    public const val MAX_EXTENSION_NAME_LENGTH: Int = 128

    /** Maximum size of [ExtensionInfo.icon] (PNG bytes). */
    public const val MAX_EXTENSION_ICON_BYTES: Int = 16 * 1024

    /** Maximum length of [ExtensionsReady.error]. */
    public const val MAX_EXTENSION_ERROR_LENGTH: Int = 1_024

    /** Maximum length (UTF-16 chars) of the JSON text of a client -> server [ExtensionMessage]. */
    public const val MAX_EXTENSION_JSON_LENGTH: Int = 16_384

    /**
     * Maximum length (UTF-16 chars) of the JSON text of a server -> client [ExtensionMessage]. Large enough for a
     * shop page of ~50 offers with Base64 items; at most 3 bytes per char keeps it below [MAX_CLIENTBOUND_BYTES].
     */
    public const val MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH: Int = 262_144

    /** Maximum number of [ExtensionInfo.dependencies] of one extension. */
    public const val MAX_EXTENSION_DEPENDENCIES: Int = 16

    /** Maximum number of keybinds in one [DeclareKeybinds]. */
    public const val MAX_KEYBINDS: Int = 128

    /**
     * Maximum absolute value of an element's x/y offset, in GUI pixels. Larger than [MAX_SIZE] because children of a
     * [ScrollPanel] are positioned inside its (possibly very tall) content area.
     */
    public const val MAX_COORDINATE: Int = 65_536

    /** Maximum width/height of an element, in GUI pixels. */
    public const val MAX_SIZE: Int = 8_192

    /** Maximum width/height of a texture referenced by an [Image]. */
    public const val MAX_TEXTURE_SIZE: Int = 65_536

    /**
     * Maximum display count of an [ItemStackData]. Counts above 99 cannot be represented by a vanilla `ItemStack`;
     * the client draws them as text (e.g. `1.2k`).
     */
    public const val MAX_ITEM_COUNT: Int = 1_000_000_000

    /**
     * Maximum size of [ItemStackData.nbt] (gzip-compressed NBT as produced by Paper's `ItemStack.serializeAsBytes()`).
     * The client additionally caps the *decompressed* size with an `NbtAccounter` quota of [MAX_ITEM_NBT_UNCOMPRESSED].
     */
    public const val MAX_ITEM_NBT_BYTES: Int = 65_536

    /** Quota (bytes) the client applies when decompressing/reading [ItemStackData.nbt]. */
    public const val MAX_ITEM_NBT_UNCOMPRESSED: Int = 2_097_152

    /** Maximum height of the scrollable content of a [ScrollPanel], in GUI pixels. */
    public const val MAX_SCROLL_CONTENT_HEIGHT: Int = 65_536

    /** Maximum number of options of a [Dropdown]. */
    public const val MAX_DROPDOWN_OPTIONS: Int = 256

    /** Maximum length of a [DropdownOption.value] (also bounded by [MAX_TEXT_FIELD_LENGTH] as a [ScreenInput]). */
    public const val MAX_OPTION_VALUE_LENGTH: Int = 256

    /** Maximum length (component JSON) of a [DropdownOption.label]. */
    public const val MAX_OPTION_LABEL_LENGTH: Int = 4_096

    /** Maximum absolute value of numeric bounds/values ([Slider], [NumericRange]); keeps doubles exact for integers. */
    public const val MAX_NUMERIC_VALUE: Double = 1.0e15

    /** Maximum display time of a [ShowToast], in milliseconds. */
    public const val MAX_TOAST_MILLIS: Int = 60_000

    /** Highest mouse button number accepted in [ScreenClick] (GLFW numbering). */
    public const val MAX_MOUSE_BUTTON: Int = 7
}

/** Thrown for every malformed, oversized or otherwise invalid message. Callers log it and drop the message. */
public class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
