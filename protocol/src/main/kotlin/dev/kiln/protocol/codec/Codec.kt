package dev.kiln.protocol.codec

/**
 * Reads and writes one value type, like Minecraft's `StreamCodec`. [decode] is the exact mirror of [encode], and
 * client and server use the same codecs, so both sides always agree on the layout. Codecs reject out-of-range or
 * malformed values with a [dev.kiln.protocol.ProtocolException] on encode as well as on decode.
 *
 * The codec of a protocol type is its companion object, e.g. `ScreenClick.encode(writer, click)`.
 */
public interface Codec<T> {
    public fun encode(writer: ProtocolWriter, value: T)

    public fun decode(reader: ProtocolReader): T

    /** This codec behind a presence flag (`boolean`, then the value if present). */
    public fun nullable(): Codec<T?> = NullableCodec(this)

    /** A varint size of at most [maxSize], then the elements; [what] names the list in error messages. */
    public fun list(maxSize: Int, what: String): Codec<List<T>> = ListCodec(this, maxSize, what)

    /** This codec with [check] applied to every value before it is encoded and after it is decoded. */
    public fun validated(check: (T) -> Unit): Codec<T> = ValidatedCodec(this, check)
}
