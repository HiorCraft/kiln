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
        val limit = when (suffix) {
            "ogg" -> ProtocolLimits.MAX_SOUND_BYTES
            "ttf", "otf" -> ProtocolLimits.MAX_FONT_BYTES
            else -> null
        }
        if (limit != null && size > limit) return "$path: larger than $limit bytes"
        return null
    }

    /** `null` if [json] (content of [path]) is valid JSON without a `kiln:` reference. */
    public fun jsonProblem(path: String, json: String): String? {
        val strings = try {
            JsonStrings(json).all()
        } catch (e: IllegalArgumentException) {
            return "$path: invalid JSON (${e.message})"
        }
        val bad = strings.firstOrNull { it.startsWith(FORBIDDEN_REFERENCE) } ?: return null
        return "$path: references '$bad' (the kiln namespace is reserved)"
    }

    /** Translation keys of extension [extensionId] start with `<id>.`. */
    public fun langKeyAllowed(extensionId: String, key: String): Boolean =
        key.length > extensionId.length + 1 && key.startsWith("$extensionId.")

    /** Strict minimal JSON reader that validates [text] and collects every string (keys and values). */
    private class JsonStrings(private val text: String) {
        private var pos = 0
        private val strings = ArrayList<String>()

        fun all(): List<String> {
            value(0)
            ws()
            require(pos == text.length) { "trailing content at $pos" }
            return strings
        }

        private fun value(depth: Int) {
            require(depth <= 64) { "nested too deeply" }
            ws()
            require(pos < text.length) { "unexpected end" }
            when (val c = text[pos]) {
                '{' -> container('}') { strings += string(); ws(); expect(':'); value(depth + 1) }
                '[' -> container(']') { value(depth + 1) }
                '"' -> strings += string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private inline fun container(close: Char, element: () -> Unit) {
            pos++
            ws()
            if (peek() == close) { pos++; return }
            while (true) {
                ws()
                element()
                ws()
                when (peek()) {
                    ',' -> pos++
                    close -> { pos++; return }
                    else -> throw IllegalArgumentException("expected ',' or '$close' at $pos")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(pos < text.length) { "unterminated string" }
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(pos < text.length) { "unterminated escape" }
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= text.length) { "bad unicode escape" }
                                sb.append(text.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("bad unicode escape"))
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number() {
            val start = pos
            while (pos < text.length && text[pos] in "+-0123456789.eE") pos++
            text.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun literal(word: String) {
            require(text.startsWith(word, pos)) { "unexpected token at $pos" }
            pos += word.length
        }

        private fun expect(c: Char) {
            require(peek() == c) { "expected '$c' at $pos" }
            pos++
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun ws() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
