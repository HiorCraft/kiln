package dev.kiln.client.ext

import dev.kiln.protocol.ExtensionPermissions

/** One permission line of an expanded consent entry. */
data class PermissionRow(val permission: String, val label: String, val requested: Boolean)

/** Human-readable permission names, in canonical order. */
object PermissionTexts {
    val LABELS: Map<String, String> = linkedMapOf(
        ExtensionPermissions.WIDGETS to "Eigene GUI-Elemente in Server-Menüs",
        ExtensionPermissions.KEYBINDS to "Eigene Tastenbelegungen",
        ExtensionPermissions.HUD to "Anzeigen im HUD",
        ExtensionPermissions.CHANNELS to "Nachrichten mit dem Server austauschen",
        ExtensionPermissions.TOASTS to "Benachrichtigungen anzeigen",
        ExtensionPermissions.SCREENS to "Eigene Menüs öffnen",
        ExtensionPermissions.INVENTORY to "Dein Inventar lesen",
        ExtensionPermissions.RESOURCES to "Ressourcen: Modelle, Texturen, Sounds, Schriften",
    )

    /** Every known permission (requested -> green check, not requested -> red X). */
    fun rows(requested: Collection<String>): List<PermissionRow> =
        ExtensionPermissions.ALL.map { PermissionRow(it, LABELS[it] ?: it, it in requested) }

    /** Height of the "Benötigt:" line block of an expanded consent entry with [count] dependencies. */
    fun dependencyHeight(count: Int): Int = if (count == 0) 0 else 12 + 11 * count

    /** "Name (id)" of each dependency, using the manifest names where known. */
    fun dependencyLabels(dependencies: List<String>, names: Map<String, String>): List<String> =
        dependencies.map { id -> names[id]?.takeIf { it != id }?.let { "$it ($id)" } ?: id }
}

/**
 * Pure state of the consent list: an accordion of extensions inside a scrollable viewport.
 *
 * Layout (GUI pixels, relative to the list top, scroll applied by the caller): every entry has a header row of
 * [headerHeight]; an expanded entry adds [detailHeight] below it. The chevron of an entry rotates from 0 (collapsed,
 * pointing right) to 1 (expanded, pointing down) over [animationMillis]. [detailHeightOf] gives entries details of
 * different heights (default: [detailHeight] for every entry).
 */
class ConsentListState(
    val count: Int,
    val headerHeight: Int = 26,
    val detailHeight: Int = 24 + ExtensionPermissions.ALL.size * 12 + 4,
    val gap: Int = 3,
    private val animationMillis: Float = 150f,
    private val detailHeightOf: ((Int) -> Int)? = null,
) {
    private val expanded = BooleanArray(count)
    private val turn = FloatArray(count)

    /** Scroll offset in pixels, always within [0, maxScroll]. */
    var scroll: Double = 0.0
        private set

    var viewportHeight: Int = 0
        private set

    fun isExpanded(index: Int): Boolean = expanded[index]

    /** Chevron rotation progress 0..1 of [index]. */
    fun chevron(index: Int): Float = turn[index]

    fun toggle(index: Int) {
        if (index !in 0 until count) return
        expanded[index] = !expanded[index]
        clampScroll()
    }

    /** Advances the chevron animations by [deltaMillis]. */
    fun animate(deltaMillis: Float) {
        val step = if (animationMillis <= 0f) 1f else deltaMillis / animationMillis
        for (i in 0 until count) {
            val target = if (expanded[i]) 1f else 0f
            turn[i] = if (turn[i] < target) minOf(target, turn[i] + step) else maxOf(target, turn[i] - step)
        }
    }

    fun entryHeight(index: Int): Int = headerHeight + if (expanded[index]) (detailHeightOf?.invoke(index) ?: detailHeight) else 0

    /** Top of entry [index] in content coordinates. */
    fun entryTop(index: Int): Int {
        var y = 0
        for (i in 0 until index) y += entryHeight(i) + gap
        return y
    }

    val contentHeight: Int
        get() = if (count == 0) 0 else (0 until count).sumOf { entryHeight(it) } + gap * (count - 1)

    val maxScroll: Int get() = (contentHeight - viewportHeight).coerceAtLeast(0)

    fun setViewport(height: Int) {
        viewportHeight = height.coerceAtLeast(0)
        clampScroll()
    }

    fun scrollBy(delta: Double) {
        scroll += delta
        clampScroll()
    }

    private fun clampScroll() {
        scroll = scroll.coerceIn(0.0, maxScroll.toDouble())
    }

    /** Entry whose **header** is at viewport y [y] (scroll applied), or -1. */
    fun headerAt(y: Double): Int {
        if (y < 0 || y >= viewportHeight) return -1
        val contentY = y + scroll
        for (i in 0 until count) {
            val top = entryTop(i)
            if (contentY >= top && contentY < top + headerHeight) return i
        }
        return -1
    }
}
