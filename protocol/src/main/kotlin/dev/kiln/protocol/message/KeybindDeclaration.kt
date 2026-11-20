package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.IdCodec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec

/**
 * A server-defined keybind. [name] and [category] are display strings (also usable as translation keys),
 * [defaultKey] is an `InputConstants` key name such as `key.keyboard.g` or `key.mouse.middle`
 * (`key.keyboard.unknown` = unbound).
 */
public data class KeybindDeclaration(val id: String, val name: String, val category: String, val defaultKey: String) {
    /** Wire codec: id, name, category, default key. */
    public companion object : Codec<KeybindDeclaration> {
        private val name = StringCodec(MAX_KEYBIND_TEXT_LENGTH, "keybind name")
        private val category = StringCodec(MAX_KEYBIND_TEXT_LENGTH, "keybind category")
        private val defaultKey = StringCodec(MAX_ID_LENGTH, "default key")

        override fun encode(writer: ProtocolWriter, value: KeybindDeclaration) {
            IdCodec.KEYBIND.encode(writer, value.id)
            name.encode(writer, value.name)
            category.encode(writer, value.category)
            defaultKey.encode(writer, value.defaultKey)
        }

        override fun decode(reader: ProtocolReader): KeybindDeclaration = KeybindDeclaration(
            id = IdCodec.KEYBIND.decode(reader),
            name = name.decode(reader),
            category = category.decode(reader),
            defaultKey = defaultKey.decode(reader),
        )
    }
}
