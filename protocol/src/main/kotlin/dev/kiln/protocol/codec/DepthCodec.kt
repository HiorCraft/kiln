package dev.kiln.protocol.codec

/**
 * A codec for the recursive GUI element tree. [depth] is the nesting depth of the value (the root has depth 1), so
 * implementations can enforce the nesting limit before they recurse. Containers implement it in their companion.
 */
public interface DepthCodec<T> {
    public fun encode(writer: ProtocolWriter, value: T, depth: Int)

    public fun decode(reader: ProtocolReader, depth: Int): T

    /** This codec as a plain [Codec] for values at [depth], e.g. the children of a container. */
    public fun atDepth(depth: Int): Codec<T> = FixedDepthCodec(this, depth)
}
