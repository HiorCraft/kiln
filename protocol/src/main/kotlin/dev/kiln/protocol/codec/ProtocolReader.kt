package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.codec.BinaryFormat.MAX_UTF8_BYTES_PER_CHAR
import dev.kiln.protocol.codec.BinaryFormat.MAX_VARINT_BYTES
import dev.kiln.protocol.codec.BinaryFormat.VARINT_CONTINUE_BIT
import dev.kiln.protocol.codec.BinaryFormat.VARINT_SEGMENT_BITS
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * Bounds-checked reader of the wire primitives, on top of [DataInput]; structured values are read by [Codec]s.
 * Every length is checked against the remaining bytes *before* anything is allocated, so a hostile length prefix
 * cannot cause large allocations.
 */
public class ProtocolReader(bytes: ByteArray) {
    private val stream = ByteArrayInputStream(bytes)
    private val input: DataInput = DataInputStream(stream)

    public val remaining: Int get() = stream.available()

    private fun need(count: Int, what: String) {
        if (count > remaining) throw ProtocolException("Truncated message: $what needs $count bytes, $remaining left")
    }

    public fun byte(): Byte { need(1, "byte"); return input.readByte() }

    public fun boolean(): Boolean = when (val b = byte().toInt()) {
        0 -> false
        1 -> true
        else -> throw ProtocolException("Invalid boolean $b")
    }

    public fun int(): Int { need(4, "int"); return input.readInt() }

    public fun float(): Float { need(4, "float"); return input.readFloat() }

    public fun double(): Double { need(8, "double"); return input.readDouble() }

    /** Unsigned LEB128 varint, at most 5 bytes. */
    public fun varInt(): Int {
        var result = 0
        for (i in 0 until MAX_VARINT_BYTES) {
            val b = byte().toInt()
            result = result or ((b and VARINT_SEGMENT_BITS) shl (7 * i))
            if (b and VARINT_CONTINUE_BIT == 0) return result
        }
        throw ProtocolException("VarInt too long")
    }

    /** Zigzag-encoded signed varint. */
    public fun signedVarInt(): Int {
        val raw = varInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    /** UTF-8 string with varint byte length; [maxLength] is in UTF-16 chars. */
    public fun string(maxLength: Int, what: String = "string"): String {
        val byteLength = varInt()
        if (byteLength < 0 || byteLength > maxLength * MAX_UTF8_BYTES_PER_CHAR) throw ProtocolException("$what too long ($byteLength bytes)")
        need(byteLength, what)
        val buffer = ByteArray(byteLength)
        input.readFully(buffer)
        val value = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer)).toString()
        } catch (e: CharacterCodingException) {
            throw ProtocolException("$what is not valid UTF-8", e)
        }
        if (value.length > maxLength) throw ProtocolException("$what too long (${value.length} > $maxLength chars)")
        return value
    }

    public fun byteArray(maxLength: Int, what: String = "byte array"): ByteArray {
        val length = varInt()
        if (length < 0 || length > maxLength) throw ProtocolException("$what too long ($length > $maxLength)")
        need(length, what)
        return ByteArray(length).also { input.readFully(it) }
    }

    /** Fails if unread bytes are left. */
    public fun expectEnd() {
        if (remaining != 0) throw ProtocolException("$remaining trailing bytes")
    }
}
