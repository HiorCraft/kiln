package dev.kiln.protocol

/**
 * Rules for the resource-pack tree `assets/<id>/…` of an extension package (protocol v6), shared by the server
 * (packing) and the client (reading, before mounting the pack):
 * - only the extension's own namespace (`assets/<id>/…`); nothing may override vanilla, kiln or another extension;
 * - only [EXTENSIONS], with per-type size limits;
 * - JSON must parse and may reference other namespaces (`minecraft:item/generated`) but never `kiln:` (kiln's own UI
 *   textures must not be reusable for a fake consent screen);
 * - language keys start with `<id>.`.
 */
public object AssetRules {
    public const val ROOT: String = "assets/"

    /** Allowed file suffixes below `assets/<id>/`. */
    public val EXTENSIONS: Set<String> = setOf("json", "png", "mcmeta", "ogg", "ttf", "otf", "fsh", "vsh", "glsl", "txt")

    private const val FORBIDDEN_REFERENCE = "kiln:"

    /** `null` if [path] (package path, `assets/…`) with [size] bytes is allowed for extension [extensionId]. */
    public fun problem(extensionId: String, path: String, size: Int): String? {
        val prefix = "$ROOT$extensionId/"
        if (!path.startsWith(prefix) || path.length == prefix.length) {
            return "$path: assets must be below $prefix (own namespace only)"
        }
        val suffix = path.substringAfterLast('.', "")
        if (suffix !in EXTENSIONS) return "$path: file type '.$suffix' is not allowed in assets (allowed: ${EXTENSIONS.joinToString()})"
        val limit = sizeLimit(suffix)
        if (limit != null && size > limit) return "$path: larger than $limit bytes"
        return null
    }

    /** Maximum size in bytes of a file with [suffix], or `null` if only the package limits apply. */
    private fun sizeLimit(suffix: String): Int? = when (suffix) {
        "ogg" -> ProtocolLimits.MAX_SOUND_BYTES
        "ttf", "otf" -> ProtocolLimits.MAX_FONT_BYTES
        else -> null
    }

    /** `null` if [json] (content of [path]) is valid JSON without a `kiln:` reference. */
    public fun jsonProblem(path: String, json: String): String? {
        val strings = try {
            JsonStrings(json).collect()
        } catch (e: IllegalArgumentException) {
            return "$path: invalid JSON (${e.message})"
        }
        val bad = strings.firstOrNull { it.startsWith(FORBIDDEN_REFERENCE) } ?: return null
        return "$path: references '$bad' (the kiln namespace is reserved)"
    }

    /** Translation keys of extension [extensionId] start with `<id>.`. */
    public fun langKeyAllowed(extensionId: String, key: String): Boolean =
        key.length > extensionId.length + 1 && key.startsWith("$extensionId.")
}
