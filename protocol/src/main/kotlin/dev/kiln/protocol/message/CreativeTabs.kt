package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolLimits.MAX_CREATIVE_ENTRIES
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter

/**
 * Play phase (v7): creative-inventory content. [reset] = `true` replaces everything the client had (first message
 * of an update); further messages of the same update append [tabs] (the server splits large updates).
 */
public data class CreativeTabs(val reset: Boolean, val tabs: List<CreativeTabData>) : ClientboundMessage {
    /** Wire codec: [reset], then the entries. */
    public companion object : Codec<CreativeTabs> {
        private val tabs = CreativeTabData.list(MAX_CREATIVE_ENTRIES, "creative entries")

        override fun encode(writer: ProtocolWriter, value: CreativeTabs) {
            writer.boolean(value.reset)
            tabs.encode(writer, value.tabs)
        }

        override fun decode(reader: ProtocolReader): CreativeTabs = CreativeTabs(reader.boolean(), tabs.decode(reader))
    }
}
