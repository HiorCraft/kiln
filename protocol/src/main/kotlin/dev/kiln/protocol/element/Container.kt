package dev.kiln.protocol.element

import dev.kiln.protocol.ProtocolLimits.MAX_ELEMENTS
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ElementCodec

/** An element with children: [Panel], [ScrollPanel] and [Modal]. Children are drawn after (above) their parent. */
public sealed interface Container : Element {
    public val children: List<Element>
}

/** Codec of the children of a container at [parentDepth]. */
internal fun childrenCodec(parentDepth: Int): Codec<List<Element>> =
    ElementCodec.atDepth(parentDepth + 1).list(MAX_ELEMENTS, "children")
