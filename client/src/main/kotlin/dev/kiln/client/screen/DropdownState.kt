package dev.kiln.client.screen

/**
 * Navigation state of an open dropdown list (pure): the highlighted option and the first visible row.
 * Keyboard navigation does not wrap around; the highlighted option is always kept visible.
 */
class DropdownState(optionCount: Int, visibleRows: Int, initialIndex: Int = -1) {
    var optionCount: Int = optionCount.coerceAtLeast(0)
        private set

    var visibleRows: Int = visibleRows.coerceAtLeast(1)
        private set

    /** Highlighted option, `-1` for none. */
    var highlighted: Int = -1
        private set

    /** Index of the first visible row. */
    var scrollTop: Int = 0
        private set

    val maxScrollTop: Int get() = (optionCount - visibleRows).coerceAtLeast(0)

    val isScrollable: Boolean get() = maxScrollTop > 0

    init {
        highlight(initialIndex)
        // open with the selection roughly centered
        if (highlighted >= 0) scrollTo(highlighted - visibleRows / 2)
    }

    /** Applies a new option list / size (tree update while open); keeps the highlight where possible. */
    fun resize(optionCount: Int, visibleRows: Int) {
        this.optionCount = optionCount.coerceAtLeast(0)
        this.visibleRows = visibleRows.coerceAtLeast(1)
        if (highlighted >= this.optionCount) highlighted = this.optionCount - 1
        scrollTo(scrollTop)
        ensureVisible()
    }

    /** Highlights [index] (clamped; `-1` clears) and scrolls it into view. */
    fun highlight(index: Int) {
        highlighted = if (optionCount == 0 || index < 0) -1 else index.coerceAtMost(optionCount - 1)
        ensureVisible()
    }

    /** Moves the highlight by [delta] options (arrow keys); with nothing highlighted, down starts at the first option. */
    fun move(delta: Int) {
        if (optionCount == 0) return
        highlight(
            when {
                highlighted < 0 && delta > 0 -> 0
                highlighted < 0 -> optionCount - 1
                else -> (highlighted + delta).coerceIn(0, optionCount - 1)
            },
        )
    }

    fun pageDown() = move(visibleRows)

    fun pageUp() = move(-visibleRows)

    fun first() = highlight(0)

    fun last() = highlight(optionCount - 1)

    /** Scrolls by [rows] (mouse wheel) without moving the highlight. */
    fun scrollBy(rows: Int) = scrollTo(scrollTop + rows)

    fun scrollTo(row: Int) {
        scrollTop = row.coerceIn(0, maxScrollTop)
    }

    /** Option index at visible [row] (0-based from the top of the list), or `-1`. */
    fun optionAtRow(row: Int): Int {
        if (row < 0 || row >= visibleRows) return -1
        val index = scrollTop + row
        return if (index < optionCount) index else -1
    }

    private fun ensureVisible() {
        if (highlighted < 0) return
        if (highlighted < scrollTop) scrollTop = highlighted
        if (highlighted >= scrollTop + visibleRows) scrollTop = highlighted - visibleRows + 1
        scrollTo(scrollTop)
    }
}
