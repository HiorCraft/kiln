package dev.kiln.protocol.codec

/** An enum constant as its ordinal (varint) in [entries]. */
public class EnumCodec<E : Enum<E>>(private val entries: List<E>, what: String) : Codec<E> {
    private val ordinal = RangedVarIntCodec(0, entries.size - 1, what)

    override fun encode(writer: ProtocolWriter, value: E): Unit = ordinal.encode(writer, value.ordinal)

    override fun decode(reader: ProtocolReader): E = entries[ordinal.decode(reader)]
}
