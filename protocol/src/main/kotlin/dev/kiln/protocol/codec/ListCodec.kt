package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException

/** A varint size (at most [maxSize]) followed by that many [element]s; [what] names the list in error messages. */
public class ListCodec<T>(private val element: Codec<T>, private val maxSize: Int, private val what: String) : Codec<List<T>> {
    override fun encode(writer: ProtocolWriter, value: List<T>) {
        checkSize(value.size)
        writer.varInt(value.size)
        for (item in value) element.encode(writer, item)
    }

    override fun decode(reader: ProtocolReader): List<T> {
        val size = reader.varInt()
        checkSize(size)
        // every element needs at least one byte; a lying size cannot make us pre-allocate a huge list
        if (size > reader.remaining) {
            throw ProtocolException("Truncated message: $size $what announced, ${reader.remaining} bytes left")
        }
        return List(size) { element.decode(reader) }
    }

    private fun checkSize(size: Int) {
        if (size < 0 || size > maxSize) throw ProtocolException("Too many $what ($size > $maxSize)")
    }
}
