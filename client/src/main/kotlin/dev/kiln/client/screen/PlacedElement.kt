package dev.kiln.client.screen

import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.ScrollPanel

/**
 * An element with its offset relative to the root's top-left corner (parents' offsets resolved, scroll offsets
 * **not** applied: children of a [ScrollPanel] are placed as if the panel were scrolled to the top).
 */
data class PlacedElement(
    val element: Element,
    val offsetX: Int,
    val offsetY: Int,
    /** Index of the parent in the flattened list, `-1` for the root. */
    val parentIndex: Int = -1,
    /** Index of the nearest enclosing [ScrollPanel] in the flattened list, `-1` if none. */
    val scrollParentIndex: Int = -1,
    /** Id of the [Modal] (a direct child of the root) this element belongs to (the modal itself included), else `null`. */
    val modalId: String? = null,
    /** Index of the last descendant in the flattened list (inclusive); equals the own index for leaves. */
    val subtreeEnd: Int = 0,
) {
    val width: Int get() = element.bounds.width
    val height: Int get() = element.bounds.height
}
