package dev.kiln.protocol.codec

import dev.kiln.protocol.element.Button
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.element.Custom
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Image
import dev.kiln.protocol.element.ItemDisplay
import dev.kiln.protocol.element.ItemSlot
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.Panel
import dev.kiln.protocol.element.ProgressBar
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_DEPTH
import dev.kiln.protocol.ProtocolLimits.MAX_ELEMENTS
import dev.kiln.protocol.element.ScrollPanel
import dev.kiln.protocol.element.Slider
import dev.kiln.protocol.element.Text
import dev.kiln.protocol.element.TextField
import dev.kiln.protocol.element.walk

/**
 * Codec of a GUI tree: every element is `varint type` followed by the element's companion codec (which starts with
 * the shared header: id, bounds, tooltip). The nesting depth is checked before anything of an element is read, so
 * hostile nesting cannot overflow the stack; element count and unique ids are checked for the whole tree, on encode
 * before anything is written and on decode before the tree is handed out.
 */
public object ElementCodec : DepthCodec<Element> {
    private const val ROOT_DEPTH = 1

    private val codecs = CodecRegistry<Element, DepthCodec<Element>>("element").apply {
        add(0, Panel)
        add(1, Text)
        add(2, Button)
        add(3, Image)
        add(4, ItemDisplay)
        add(5, ProgressBar)
        add(6, TextField)
        add(7, Custom)
        add(8, ItemSlot)
        add(9, Dropdown)
        add(10, Checkbox)
        add(11, Slider)
        add(12, ScrollPanel)
        add(13, Modal)
    }

    /** Validates a tree without keeping its encoding; throws [ProtocolException]. */
    public fun validate(root: Element) {
        encode(ProtocolWriter(), root)
    }

    /** Encodes the tree [root]. */
    public fun encode(writer: ProtocolWriter, root: Element) {
        checkTree(root)
        encode(writer, root, ROOT_DEPTH)
    }

    /** Decodes a whole tree. */
    public fun decode(reader: ProtocolReader): Element = decode(reader, ROOT_DEPTH).also(::checkTree)

    override fun encode(writer: ProtocolWriter, value: Element, depth: Int) {
        checkDepth(depth)
        val type = codecs.idOf(value)
        writer.varInt(type)
        codecs.codecFor(type).encode(writer, value, depth)
    }

    override fun decode(reader: ProtocolReader, depth: Int): Element {
        checkDepth(depth)
        return codecs.codecFor(reader.varInt()).decode(reader, depth)
    }

    private fun checkDepth(depth: Int) {
        if (depth > MAX_DEPTH) throw ProtocolException("GUI tree deeper than $MAX_DEPTH")
    }

    private fun checkTree(root: Element) {
        val ids = HashSet<String>()
        for (element in root.walk()) {
            if (ids.size == MAX_ELEMENTS) throw ProtocolException("GUI tree has more than $MAX_ELEMENTS elements")
            if (!ids.add(element.id)) throw ProtocolException("Duplicate element id '${element.id}'")
        }
    }
}
