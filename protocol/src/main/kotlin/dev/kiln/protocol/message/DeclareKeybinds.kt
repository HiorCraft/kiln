package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_KEYBINDS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.requireDistinct

/** Replaces the complete set of server keybinds. */
public data class DeclareKeybinds(val keybinds: List<KeybindDeclaration>) : ClientboundMessage {
    /** Wire codec: the keybinds, ids distinct. */
    public companion object : Codec<DeclareKeybinds> {
        private val keybinds = KeybindDeclaration.list(MAX_KEYBINDS, "keybinds")
            .validated { keybinds -> requireDistinct(keybinds.map { it.id }) { "Duplicate keybind id '$it'" } }

        override fun encode(writer: ProtocolWriter, value: DeclareKeybinds): Unit =
            keybinds.encode(writer, value.keybinds)

        override fun decode(reader: ProtocolReader): DeclareKeybinds = DeclareKeybinds(keybinds.decode(reader))
    }
}
