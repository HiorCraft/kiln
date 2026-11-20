package dev.kiln.protocol

/** Shared validation rules for ids. */
public object Ids {
    private val ELEMENT_ID = Regex("[A-Za-z0-9_.:/-]{1,${ProtocolLimits.MAX_ID_LENGTH}}")
    private val EXTENSION_ID = Regex("[a-z0-9_.-]{1,${ProtocolLimits.MAX_ID_LENGTH}}")
    private val LUA_EXTENSION_ID = Regex("[a-z0-9_]{1,32}")
    private val EXTENSION_NAME = Regex("[a-z0-9_]{1,32}")

    /** Element ids: `[A-Za-z0-9_.:/-]`, 1..64 chars. */
    public fun isValidElementId(id: String): Boolean = ELEMENT_ID.matches(id)

    /** Keybind ids follow the element id rules. */
    public fun isValidKeybindId(id: String): Boolean = ELEMENT_ID.matches(id)

    /** Legacy ids announced in [Hello.extensions] (Fabric mod ids): `[a-z0-9_.-]`, 1..64 chars. */
    public fun isValidExtensionId(id: String): Boolean = EXTENSION_ID.matches(id)

    /** Ids of Lua extension packages (`extension.json` `id`; namespace of their widgets/channels/textures): `[a-z0-9_]{1,32}`. */
    public fun isValidLuaExtensionId(id: String): Boolean = LUA_EXTENSION_ID.matches(id)

    /** Channel and widget names inside an extension (the path of `<ext>:<name>`): `[a-z0-9_]{1,32}`. */
    public fun isValidExtensionName(name: String): Boolean = EXTENSION_NAME.matches(name)

    /**
     * Namespaces a Lua extension id must never use. The id is the namespace of the extension's textures
     * (`<id>:textures/...`) and the prefix of its translation keys (`<id>.…`), so an extension called like the game, the
     * mod loader or a platform library could replace their textures and texts (fake buttons, consent texts, ...):
     *
     * - `minecraft`: every vanilla resource (textures, GUI sprites, language keys under `minecraft:`);
     * - `realms`, `mojang`: vanilla Realms UI and Mojang assets/branding shipped with the client;
     * - `brigadier`, `java`: namespaces of the command library and the JVM that appear in ids/keys of the client;
     * - `kiln`: this runtime itself (consent screen, loading screen, its own texts);
     * - `fabric` and every id starting with `fabric` (`fabricloader`, `fabric_api`, `fabric_language_kotlin`, ...):
     *   Fabric Loader, Fabric API modules;
     * - `c`: the shared "common" tag/convention namespace used by Fabric and other loaders;
     * - `forge`, `neoforge`, `quilt` (+ prefix `quilt`): other loaders' namespaces, also present on some clients
     *   (Sinytra/Quilt compatibility layers).
     *
     * The client additionally rejects ids of installed mods and any texture or translation key that already exists.
     */
    public val RESERVED_NAMESPACES: Set<String> = setOf(
        "minecraft", "realms", "mojang", "brigadier", "java", "kiln", "fabric", "c", "forge", "neoforge", "quilt",
    )

    /** Reserved prefixes (see [RESERVED_NAMESPACES]): every id starting with one of them is reserved. */
    public val RESERVED_NAMESPACE_PREFIXES: List<String> = listOf("fabric", "quilt")

    /** `true` if [id] is a reserved namespace ([RESERVED_NAMESPACES], [RESERVED_NAMESPACE_PREFIXES]). */
    public fun isReservedNamespace(id: String): Boolean =
        id in RESERVED_NAMESPACES || RESERVED_NAMESPACE_PREFIXES.any { id.startsWith(it) }

    /** A readable reason why [id] cannot be a Lua extension id because it is reserved, or `null`. */
    public fun reservedNamespaceProblem(id: String): String? = if (isReservedNamespace(id)) {
        "extension id '$id' is a reserved namespace (it would replace textures/texts of the game, the mod loader or kiln)"
    } else {
        null
    }
}
