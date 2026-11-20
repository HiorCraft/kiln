package dev.kiln.protocol.codec

import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH

/** An identifier of at most [MAX_ID_LENGTH] chars that must satisfy one of the [Ids] rules. */
public class IdCodec private constructor(private val what: String, private val isValid: (String) -> Boolean) : Codec<String> {
    override fun encode(writer: ProtocolWriter, value: String) {
        checkValid(value)
        writer.string(value, MAX_ID_LENGTH, what)
    }

    override fun decode(reader: ProtocolReader): String = reader.string(MAX_ID_LENGTH, what).also(::checkValid)

    private fun checkValid(id: String) {
        if (!isValid(id)) throw ProtocolException("Invalid $what '$id'")
    }

    public companion object {
        public val ELEMENT: IdCodec = IdCodec("element id", Ids::isValidElementId)
        public val KEYBIND: IdCodec = IdCodec("keybind id", Ids::isValidKeybindId)

        /** Legacy client extension ids of [dev.kiln.protocol.message.Hello]. */
        public val LEGACY_EXTENSION: IdCodec = IdCodec("extension id", Ids::isValidExtensionId)
        public val LUA_EXTENSION: IdCodec = IdCodec("extension id", Ids::isValidLuaExtensionId)
    }
}
