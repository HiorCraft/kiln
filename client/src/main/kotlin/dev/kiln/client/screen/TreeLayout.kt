package dev.kiln.client.screen

import dev.kiln.protocol.Container
import dev.kiln.protocol.Element
import dev.kiln.protocol.Modal
import dev.kiln.protocol.ScrollPanel

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

/** Integer rectangle with exclusive right/bottom edge (screen GUI pixels). */
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
    val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0

    fun contains(x: Double, y: Double): Boolean = x >= x0 && y >= y0 && x < x1 && y < y1

    fun intersect(other: Rect): Rect =
        Rect(maxOf(x0, other.x0), maxOf(y0, other.y0), minOf(x1, other.x1), minOf(y1, other.y1))
            .let { if (it.isEmpty) Rect(it.x0, it.y0, it.x0, it.y0) else it }

    companion object {
        fun of(x: Int, y: Int, width: Int, height: Int): Rect = Rect(x, y, x + width, y + height)
    }
}

/** Screen geometry of one placed element for the current frame: scrolled position and the clip of its scroll parents. */
data class Geometry(val rect: Rect, val clip: Rect?) {
    /** `true` if ([x], [y]) is inside the element and inside its visible (clipped) area. */
    fun hit(x: Double, y: Double): Boolean = rect.contains(x, y) && (clip == null || clip.contains(x, y))
}

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

/**
 * Keeps one stateful object (vanilla widget, widget context, ...) per element id across tree updates.
 * Objects whose id survives an update are reused (so focus, cursor and text survive), new ids get a new object,
 * vanished ids are disposed.
 */
class KeyedPool<S, W>(
    private val idOf: (S) -> String,
    private val create: (S) -> W,
    private val update: (W, S) -> Unit,
    private val dispose: (W) -> Unit = {},
    /** Whether an existing object can represent the new spec (e.g. same element kind); otherwise it is recreated. */
    private val compatible: (W, S) -> Boolean = { _, _ -> true },
) {
    private val entries = LinkedHashMap<String, W>()

    val size: Int get() = entries.size

    operator fun get(id: String): W? = entries[id]

    val values: Collection<W> get() = entries.values

    /** Applies [specs] (in order); returns the objects in the order of [specs]. */
    fun sync(specs: List<S>): List<W> {
        val next = LinkedHashMap<String, W>()
        for (spec in specs) {
            val id = idOf(spec)
            if (id in next) continue // ids are unique per tree; be defensive anyway
            val existing = entries.remove(id)
            val target = if (existing != null && compatible(existing, spec)) {
                existing
            } else {
                existing?.let(dispose)
                create(spec)
            }
            update(target, spec)
            next[id] = target
        }
        entries.values.forEach(dispose)
        entries.clear()
        entries.putAll(next)
        return next.values.toList()
    }

    fun clear() {
        entries.values.forEach(dispose)
        entries.clear()
    }
}
