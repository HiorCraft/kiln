package dev.kiln.server.runtime.screen

import dev.kiln.server.api.screen.Layout
import dev.kiln.server.api.screen.ScreenElement
import dev.kiln.server.api.screen.TextElement
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Resolves the layout of a panel into absolute (parent-relative) child positions. The wire protocol only knows
 * absolute [dev.kiln.protocol.Bounds], so `Column`/`Row` never reach the client.
 *
 * - [Layout.Absolute]: children keep their own x/y.
 * - [Layout.Column]: y = padding + sum of the previous children's heights and gaps; x = padding + child.x (indent).
 * - [Layout.Row]: x = padding + sum of the previous children's widths and gaps; y = padding + child.y (offset).
 *
 * Children are never resized; content larger than the panel simply overflows (the client does not clip).
 */
object LayoutResolver {
    /** Approximate advance of the vanilla font, used for auto-sized text inside a [Layout.Row]. */
    const val CHAR_WIDTH_ESTIMATE = 6

    fun resolve(layout: Layout, children: List<ScreenElement>): List<Position> = when (layout) {
        Layout.Absolute -> children.map { Position(it.x, it.y) }
        is Layout.Column -> {
            var cursor = layout.padding
            children.map { child ->
                val pos = Position(layout.padding + child.x, cursor)
                cursor += child.height + layout.gap
                pos
            }
        }
        is Layout.Row -> {
            var cursor = layout.padding
            children.map { child ->
                val pos = Position(cursor, layout.padding + child.y)
                cursor += layoutWidth(child) + layout.gap
                pos
            }
        }
    }

    /**
     * Width a child occupies in a row. Text with `width = 0` is sized by the client to its content; the server can
     * only estimate that width from the plain text.
     */
    fun layoutWidth(child: ScreenElement): Int =
        if (child is TextElement && child.width == 0) {
            PlainTextComponentSerializer.plainText().serialize(child.text).length * CHAR_WIDTH_ESTIMATE
        } else {
            child.width
        }
}
