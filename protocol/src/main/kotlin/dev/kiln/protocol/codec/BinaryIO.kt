package dev.kiln.protocol.codec

import dev.kiln.protocol.Color
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream

/**
 * Bounds-checked reader on top of [DataInput]. Every length is checked against the remaining bytes *before*
 * anything is allocated, so a hostile length prefix cannot cause large allocations.
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
        for (i in 0 until 5) {
            val b = byte().toInt()
            result = result or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        throw ProtocolException("VarInt too long")
    }

    /** Zigzag-encoded signed varint. */
    public fun signedVarInt(): Int {
        val raw = varInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    /** Varint that must lie in [min]..[max]. */
    public fun varInt(min: Int, max: Int, what: String): Int {
        val value = varInt()
        if (value < min || value > max) throw ProtocolException("$what out of range: $value (allowed $min..$max)")
        return value
    }

    /** UTF-8 string with varint byte length; [maxLength] is in UTF-16 chars. */
    public fun string(maxLength: Int, what: String = "string"): String {
        val byteLength = varInt()
        if (byteLength < 0 || byteLength > maxLength * 3) throw ProtocolException("$what too long ($byteLength bytes)")
        need(byteLength, what)
        val buffer = ByteArray(byteLength)
        input.readFully(buffer)
        val decoder = Charsets.UTF_8.newDecoder()
        val value = try {
            decoder.decode(java.nio.ByteBuffer.wrap(buffer)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ProtocolException("$what is not valid UTF-8", e)
        }
        if (value.length > maxLength) throw ProtocolException("$what too long (${value.length} > $maxLength chars)")
        return value
    }

    public fun nullableString(maxLength: Int, what: String = "string"): String? =
        if (boolean()) string(maxLength, what) else null

    public fun byteArray(maxLength: Int, what: String = "byte array"): ByteArray {
        val length = varInt()
        if (length < 0 || length > maxLength) throw ProtocolException("$what too long ($length > $maxLength)")
        need(length, what)
        return ByteArray(length).also { input.readFully(it) }
    }

    public fun resourceId(what: String = "resource id"): ResourceId {
        val raw = string(ProtocolLimits.MAX_RESOURCE_ID_LENGTH, what)
        return ResourceId.tryParse(raw) ?: throw ProtocolException("Invalid $what '$raw'")
    }

    public fun nullableResourceId(what: String = "resource id"): ResourceId? = if (boolean()) resourceId(what) else null

    public fun color(): Color = Color(int())

    public fun nullableColor(): Color? = if (boolean()) color() else null

    public inline fun <T> list(maxSize: Int, what: String, readElement: () -> T): List<T> {
        val size = varInt()
        if (size < 0 || size > maxSize) throw ProtocolException("Too many $what ($size > $maxSize)")
        // every element needs at least one byte; prevents huge pre-sizing from a lying count
        if (size > remaining) throw ProtocolException("Truncated message: $size $what announced, $remaining bytes left")
        val list = ArrayList<T>(size)
        repeat(size) { list += readElement() }
        return list
    }

    /** Fails if unread bytes are left. */
    public fun expectEnd() {
        if (remaining != 0) throw ProtocolException("$remaining trailing bytes")
    }
}

/** Writer on top of [DataOutput], mirror of [ProtocolReader]. */
public class ProtocolWriter {
    private val buffer = ByteArrayOutputStream()
    private val output: DataOutput = DataOutputStream(buffer)

    public val size: Int get() = buffer.size()

    public fun toByteArray(): ByteArray = buffer.toByteArray()

    public fun byte(value: Int) { output.writeByte(value) }

    public fun boolean(value: Boolean) { output.writeBoolean(value) }

    public fun int(value: Int) { output.writeInt(value) }

    public fun float(value: Float) { output.writeFloat(value) }

    public fun double(value: Double) { output.writeDouble(value) }

    public fun varInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            output.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.writeByte(v)
    }

    public fun signedVarInt(value: Int) { varInt((value shl 1) xor (value shr 31)) }

    public fun varInt(value: Int, min: Int, max: Int, what: String) {
        if (value < min || value > max) throw ProtocolException("$what out of range: $value (allowed $min..$max)")
        varInt(value)
    }

    public fun string(value: String, maxLength: Int, what: String = "string") {
        if (value.length > maxLength) throw ProtocolException("$what too long (${value.length} > $maxLength chars)")
        val bytes = value.toByteArray(Charsets.UTF_8)
        varInt(bytes.size)
        output.write(bytes)
    }

    public fun nullableString(value: String?, maxLength: Int, what: String = "string") {
        boolean(value != null)
        if (value != null) string(value, maxLength, what)
    }

    public fun byteArray(value: ByteArray, maxLength: Int, what: String = "byte array") {
        if (value.size > maxLength) throw ProtocolException("$what too long (${value.size} > $maxLength)")
        varInt(value.size)
        output.write(value)
    }

    public fun resourceId(value: ResourceId) { string(value.toString(), ProtocolLimits.MAX_RESOURCE_ID_LENGTH, "resource id") }

    public fun nullableResourceId(value: ResourceId?) {
        boolean(value != null)
        if (value != null) resourceId(value)
    }

    public fun color(value: Color) { int(value.argb) }

    public fun nullableColor(value: Color?) {
        boolean(value != null)
        if (value != null) color(value)
    }

    public inline fun <T> list(values: List<T>, maxSize: Int, what: String, writeElement: (T) -> Unit) {
        if (values.size > maxSize) throw ProtocolException("Too many $what (${values.size} > $maxSize)")
        varInt(values.size)
        values.forEach(writeElement)
    }
}
