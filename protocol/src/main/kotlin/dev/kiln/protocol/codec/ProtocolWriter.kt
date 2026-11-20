package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.codec.BinaryFormat.VARINT_CONTINUE_BIT
import dev.kiln.protocol.codec.BinaryFormat.VARINT_SEGMENT_BITS
import java.io.ByteArrayOutputStream
import java.io.DataOutput
import java.io.DataOutputStream

/**
 * Writer of the wire primitives on top of [DataOutput], mirror of [ProtocolReader]; structured values are written
 * by [Codec]s.
 */
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
        while (v and VARINT_SEGMENT_BITS.inv() != 0) {
            output.writeByte((v and VARINT_SEGMENT_BITS) or VARINT_CONTINUE_BIT)
            v = v ushr 7
        }
        output.writeByte(v)
    }

    public fun signedVarInt(value: Int) { varInt((value shl 1) xor (value shr 31)) }

    public fun string(value: String, maxLength: Int, what: String = "string") {
        if (value.length > maxLength) throw ProtocolException("$what too long (${value.length} > $maxLength chars)")
        val bytes = value.toByteArray(Charsets.UTF_8)
        varInt(bytes.size)
        output.write(bytes)
    }

    public fun byteArray(value: ByteArray, maxLength: Int, what: String = "byte array") {
        if (value.size > maxLength) throw ProtocolException("$what too long (${value.size} > $maxLength)")
        varInt(value.size)
        output.write(value)
    }
}
