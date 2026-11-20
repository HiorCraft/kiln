package dev.kiln.protocol.codec

/** Wire-format constants shared by [ProtocolReader] and [ProtocolWriter]. */
internal object BinaryFormat {
    /** Longest encoding of a 32-bit LEB128 varint. */
    const val MAX_VARINT_BYTES = 5

    /** Payload bits of one varint byte. */
    const val VARINT_SEGMENT_BITS = 0x7F

    /** Set on every varint byte but the last. */
    const val VARINT_CONTINUE_BIT = 0x80

    /** A UTF-16 char takes at most this many UTF-8 bytes (surrogate pairs: 4 bytes for 2 chars). */
    const val MAX_UTF8_BYTES_PER_CHAR = 3
}
