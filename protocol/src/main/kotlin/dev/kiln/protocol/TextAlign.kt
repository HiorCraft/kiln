package dev.kiln.protocol

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.EnumCodec

public enum class TextAlign {
    LEFT, CENTER, RIGHT;

    /** Wire codec: the ordinal as varint. */
    public companion object : Codec<TextAlign> by EnumCodec(TextAlign.entries, "text align")
}
