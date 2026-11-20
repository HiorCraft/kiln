package dev.kiln.protocol

private const val MAX_JSON_DEPTH = 64
private const val UNICODE_ESCAPE_DIGITS = 4

/** Strict minimal JSON reader that validates [text] and collects every string (keys and values). */
internal class JsonStrings(private val text: String) {
    private var pos = 0
    private val strings = ArrayList<String>()

    /** Validates the whole text; throws [IllegalArgumentException] for invalid JSON. */
    fun collect(): List<String> {
        readValue(0)
        skipWhitespace()
        require(pos == text.length) { "trailing content at $pos" }
        return strings
    }

    private fun readValue(depth: Int) {
        require(depth <= MAX_JSON_DEPTH) { "nested too deeply" }
        skipWhitespace()
        require(pos < text.length) { "unexpected end" }
        when (val c = text[pos]) {
            '{' -> readContainer('}') { readMember(depth) }
            '[' -> readContainer(']') { readValue(depth + 1) }
            '"' -> strings += readString()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("unexpected '$c' at $pos")
        }
    }

    private fun readMember(depth: Int) {
        strings += readString()
        skipWhitespace()
        expect(':')
        readValue(depth + 1)
    }

    private inline fun readContainer(close: Char, readElement: () -> Unit) {
        pos++
        skipWhitespace()
        if (peek() == close) {
            pos++
            return
        }
        while (true) {
            skipWhitespace()
            readElement()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                close -> {
                    pos++
                    return
                }
                else -> throw IllegalArgumentException("expected ',' or '$close' at $pos")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            require(pos < text.length) { "unterminated string" }
            val c = text[pos++]
            when {
                c == '"' -> return result.toString()
                c == '\\' -> result.append(readEscape())
                c < ' ' -> throw IllegalArgumentException("control character in string")
                else -> result.append(c)
            }
        }
    }

    /** The character of the escape sequence after a backslash. */
    private fun readEscape(): Char {
        require(pos < text.length) { "unterminated escape" }
        return when (val e = text[pos++]) {
            '"', '\\', '/' -> e
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> throw IllegalArgumentException("bad escape '\\$e'")
        }
    }

    private fun readUnicodeEscape(): Char {
        require(pos + UNICODE_ESCAPE_DIGITS <= text.length) { "bad unicode escape" }
        val code = text.substring(pos, pos + UNICODE_ESCAPE_DIGITS).toIntOrNull(16) ?: throw IllegalArgumentException("bad unicode escape")
        pos += UNICODE_ESCAPE_DIGITS
        return code.toChar()
    }

    private fun readNumber() {
        val start = pos
        while (pos < text.length && text[pos] in "+-0123456789.eE") pos++
        text.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
    }

    private fun readLiteral(word: String) {
        require(text.startsWith(word, pos)) { "unexpected token at $pos" }
        pos += word.length
    }

    private fun expect(c: Char) {
        require(peek() == c) { "expected '$c' at $pos" }
        pos++
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
