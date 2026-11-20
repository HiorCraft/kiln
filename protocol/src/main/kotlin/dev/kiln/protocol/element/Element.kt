package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds

/**
 * A node of the declarative GUI tree. Every element has a stable [id] (unique within its tree) so the client can
 * keep focus, text content, input values and scroll position across [UpdateScreen]s, and [bounds] relative to its
 * parent (for children of a [ScrollPanel]: relative to the top of its scrollable content). The root element is
 * centered on the screen, or placed at its own x/y for [OpenScreen.fullscreen] screens.
 *
 * Text values ([Text.text], [Button.label], tooltips, placeholders, labels) are Minecraft text components
 * serialized as JSON.
 *
 * Wire layout of every element: `varint type`, `string id`, bounds, `nullable string tooltip`, type-specific fields.
 */
public sealed interface Element {
    public val id: String
    public val bounds: Bounds

    /**
     * Component JSON shown while the mouse is over the element (line breaks via `\n`). For [ItemDisplay] /
     * [ItemSlot] it is appended below the vanilla item tooltip.
     */
    public val tooltip: String?
}

/** Depth-first (pre-order) traversal of [this] element and all descendants. */
public fun Element.walk(): Sequence<Element> {
    val root = this
    return sequence {
        yield(root)
        if (root is Container) root.children.forEach { yieldAll(it.walk()) }
    }
}

/** Finds the element with [id] in this tree. */
public fun Element.find(id: String): Element? = walk().firstOrNull { it.id == id }

/** The topmost (last) [Modal] among the root's children, or `null` if no modal is open. */
public fun Element.topModal(): Modal? = (this as? Container)?.children?.lastOrNull { it is Modal } as Modal?
