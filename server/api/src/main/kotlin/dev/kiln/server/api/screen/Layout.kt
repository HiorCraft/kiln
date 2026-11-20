package dev.kiln.server.api.screen

/** How a panel positions its children. */
public sealed interface Layout {
    /** Children use their own x/y. */
    public data object Absolute : Layout

    /** Children are stacked top to bottom; a child's y is ignored, its x is an extra indent. */
    public data class Column(val gap: Int = 2, val padding: Int = 4) : Layout

    /** Children are placed left to right; a child's x is ignored, its y is an extra offset. */
    public data class Row(val gap: Int = 2, val padding: Int = 4) : Layout
}
