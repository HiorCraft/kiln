package dev.kiln.protocol.element

import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.EnumCodec

public enum class CheckboxStyle {
    CHECKBOX, TOGGLE;

    /** Wire codec: the ordinal as varint. */
    public companion object : Codec<CheckboxStyle> by EnumCodec(CheckboxStyle.entries, "checkbox style")
}
