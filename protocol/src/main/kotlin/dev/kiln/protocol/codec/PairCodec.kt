package dev.kiln.protocol.codec

/** A [Pair]: [first], then [second]. */
public class PairCodec<A, B>(private val first: Codec<A>, private val second: Codec<B>) : Codec<Pair<A, B>> {
    override fun encode(writer: ProtocolWriter, value: Pair<A, B>) {
        first.encode(writer, value.first)
        second.encode(writer, value.second)
    }

    override fun decode(reader: ProtocolReader): Pair<A, B> = first.decode(reader) to second.decode(reader)
}
