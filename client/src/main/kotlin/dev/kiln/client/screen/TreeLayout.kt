package dev.kiln.client.screen

import dev.kiln.protocol.element.Container
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.ScrollPanel

/** Pure layout helpers for the server GUI tree (no Minecraft types). */
object TreeLayout {

    /**
     * Flattens [root] depth-first (pre-order = draw order: parents below children, later siblings on top). The
     * subtrees of [Modal]s (direct children of the root) are moved to the end, in their order, so modals are always
     * drawn above the rest of the tree. Offsets are relative to the root's own top-left corner (root at (0, 0)).
     */
    fun flatten(root: Element): List<PlacedElement> {
        val out = ArrayList<PlacedElement>()
        fun visit(element: Element, x: Int, y: Int, parent: Int, scrollParent: Int, modalId: String?) {
            val index = out.size
            out += PlacedElement(element, x, y, parent, scrollParent, modalId, index)
            if (element is Container) {
                val childScrollParent = if (element is ScrollPanel) index else scrollParent
                val children = if (index == 0) element.children.filter { it !is Modal } else element.children
                for (child in children) {
                    visit(child, x + child.bounds.x, y + child.bounds.y, index, childScrollParent, modalId)
                }
            }
            if (index == 0 && element is Container) {
                for (modal in element.children.filterIsInstance<Modal>()) {
                    visit(modal, modal.bounds.x, modal.bounds.y, 0, -1, modal.id)
                }
            }
            out[index] = out[index].copy(subtreeEnd = out.size - 1)
        }
        visit(root, 0, 0, -1, -1, null)
        return out
    }

    /** Screen position of the root: centered (unless [fullscreen]), then shifted by the root's own x/y. */
    fun rootOrigin(root: Element, screenWidth: Int, screenHeight: Int, fullscreen: Boolean = false): Pair<Int, Int> =
        if (fullscreen) root.bounds.x to root.bounds.y
        else (screenWidth - root.bounds.width) / 2 + root.bounds.x to (screenHeight - root.bounds.height) / 2 + root.bounds.y

    /**
     * Resolves the screen rectangles of all [placed] elements: origin, the current scroll offsets of enclosing scroll
     * panels ([scrollOf], by scroll panel id) and the clip rectangle (intersection of all enclosing scroll panels).
     * [rootSize], if set, replaces the root's own size (fullscreen root stretched to the window).
     */
    fun geometry(
        placed: List<PlacedElement>,
        originX: Int,
        originY: Int,
        scrollOf: (String) -> Int,
        rootSize: Pair<Int, Int>? = null,
    ): List<Geometry> {
        val shift = IntArray(placed.size)
        val out = ArrayList<Geometry>(placed.size)
        for ((i, p) in placed.withIndex()) {
            val sp = p.scrollParentIndex
            val clip: Rect?
            if (sp >= 0) {
                shift[i] = shift[sp] + scrollOf(placed[sp].element.id)
                val parentGeometry = out[sp]
                clip = parentGeometry.clip?.intersect(parentGeometry.rect) ?: parentGeometry.rect
            } else {
                clip = null
            }
            val (w, h) = if (i == 0 && rootSize != null) rootSize else p.width to p.height
            out += Geometry(Rect.of(originX + p.offsetX, originY + p.offsetY - shift[i], w, h), clip)
        }
        return out
    }

    /** `true` if [placed] cannot receive input because a modal other than its own is the topmost one. */
    fun isBlocked(placed: PlacedElement, topModalId: String?): Boolean = topModalId != null && placed.modalId != topModalId

    /**
     * Index of the topmost (last drawn) element at ([x], [y]) that is visible there, not blocked by [topModalId] and
     * matches [filter], or `-1`.
     */
    fun topmostAt(
        placed: List<PlacedElement>,
        geometry: List<Geometry>,
        x: Double,
        y: Double,
        topModalId: String?,
        filter: (PlacedElement) -> Boolean,
    ): Int {
        for (i in placed.indices.reversed()) {
            val p = placed[i]
            if (isBlocked(p, topModalId) || !filter(p)) continue
            if (geometry[i].hit(x, y)) return i
        }
        return -1
    }
}
