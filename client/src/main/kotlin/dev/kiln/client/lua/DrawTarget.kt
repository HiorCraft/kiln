package dev.kiln.client.lua

/**
 * What Lua drawing (`g`) needs from the renderer, in absolute GUI pixels. Clipping to the widget / HUD area is set
 * up by the caller around the Lua call; `g:clip` nests further clips with [pushClip] / [popClip]. Implemented on top
 * of Minecraft's `GuiGraphicsExtractor` by the runtime and by a recorder in tests (Lua API v2 methods have no-op
 * defaults there).
 */
interface DrawTarget {
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int)

    /** Vertical gradient from [top] to [bottom]. */
    fun gradient(x0: Int, y0: Int, x1: Int, y1: Int, top: Int, bottom: Int) = fill(x0, y0, x1, y1, top)

    /** A line of [thickness] pixels from ([x1], [y1]) to ([x2], [y2]). */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int)

    fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean)

    /** [text] scaled by [scale] around its top-left corner at ([x], [y]). */
    fun scaledText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) = text(text, x.toInt(), y.toInt(), color, shadow)

    fun textWidth(text: String): Int

    /** [text] word-wrapped to [width] pixels (lines without trailing spaces). */
    fun wrap(text: String, width: Int): List<String> = listOf(text)

    /** Size of texture [id] (`namespace:path`) if it may be drawn, else `null`. */
    fun textureSize(id: String): IntArray?

    fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int)

    /** Vanilla GUI sprite [id] (already checked against the allowlist) stretched / nine-sliced to [w] x [h]. */
    fun sprite(id: String, x: Int, y: Int, w: Int, h: Int) {}

    /**
     * Draws item [itemId] scaled to [size] x [size] pixels; unknown ids draw nothing. [count] > 1 is drawn as the
     * (abbreviated) slot count when [decorations] is set.
     */
    fun item(itemId: String, x: Int, y: Int, size: Int, count: Int = 1, decorations: Boolean = false)

    /** Draws a decoded item (Lua item handle) like [item], with all its data components (glint, model, ...). */
    fun itemStack(ref: ItemRef, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) {}

    /** Shows [text] as tooltip at the mouse after this frame (widgets and screens; ignored elsewhere). */
    fun tooltip(text: String)

    /** Shows the given lines as tooltip at the mouse after this frame. */
    fun tooltipLines(lines: List<String>) = tooltip(lines.joinToString("\n"))

    /** Vanilla item tooltip of [ref] (plus [extra] lines) at ([x], [y]) after this frame. */
    fun itemTooltip(ref: ItemRef, x: Int, y: Int, extra: List<String>) {}

    /** Intersects the clip with the rectangle (absolute) until the matching [popClip]. */
    fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int) {}

    fun popClip() {}

    /** Mouse cursor for this frame: `default`, `hand`, `text`, `crosshair`, `resize_ns`, `resize_ew`, `move`, `not_allowed`. */
    fun cursor(name: String) {}
}

/** A decoded item stack held by Lua as an opaque handle (`kiln.items.decode`, `player().inventory()`). */
interface ItemRef {
    /** Item type, e.g. `minecraft:diamond_sword`. */
    val id: String

    /** Display name (custom name or item name) as plain text. */
    val name: String

    val maxStackSize: Int
}

/** One non-empty slot of the player's inventory (`kiln.player().inventory()`). */
class InventorySlot(val slot: Int, val item: ItemRef, val count: Int)

/** GUI-scaled window size and GUI scale. */
data class ViewportInfo(val width: Int, val height: Int, val scale: Double)
