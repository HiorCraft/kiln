package dev.kiln.client.lua.graphics

import dev.kiln.client.lua.sandbox.LuaExecution
import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.items.ItemHandle
import dev.kiln.client.lua.items.LuaItems
import dev.kiln.client.lua.optionBoolean
import dev.kiln.client.lua.optionDouble
import dev.kiln.client.lua.optionString
import dev.kiln.client.lua.withoutSelf
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import kotlin.math.floor

/**
 * The drawing context `g` of one extension. One table per extension; its functions draw into the [DrawTarget] of
 * the call in progress ([draw]) with coordinates relative to the widget / HUD / screen origin. Outside a render call
 * `g` is inert (an error), so a script cannot keep it and draw later. `g` is a capability: an extension that hands it
 * to an imported API extension lets that one draw (nothing else).
 *
 * Methods (called as `g:name(...)`; colors are ARGB numbers such as `0xFF2A2F2C`):
 * - `rect(x, y, w, h, color)`, `outline(x, y, w, h, color)`, `gradient(x, y, w, h, top, bottom)`
 * - `line(x1, y1, x2, y2, color [, thickness = 1])`
 * - `arc(cx, cy, radius, thickness, from, to, color)` – upper half ring around (cx, cy); from/to are fractions 0..1
 *   from the left end over the top to the right end (gauge)
 * - `text(str, x, y [, {align = "left"|"center"|"right", color = 0xFFFFFFFF, shadow = true, scale = 1}])` -> width
 *   (scaled)
 * - `textWidth(str)` -> width, `wrap(str, width)` -> list of lines
 * - `texture(id, x, y, w, h [, u, v, rw, rh, tw, th])` – `id` = `"<ext>:textures/…png"` (or without namespace);
 *   defaults: u = v = 0, tw/th = the texture's size, rw/rh = tw/th (whole texture stretched to w x h)
 * - `sprite(id, x, y, w, h)` – vanilla GUI sprite from [SPRITES] (nine-sliced like vanilla buttons)
 * - `item(itemIdOrHandle, x, y [, size | {size = 16, count = 1, decorations = true}])`
 * - `itemTooltip(handle, mx, my [, lines])`, `tooltip(textOrLines)` – screens and widgets only
 * - `clip(x, y, w, h, fn)` – runs `fn()` with drawing clipped to the rectangle (intersected with outer clips)
 */
class LuaGraphics(private val extension: LuaExtension) {
    private var target: DrawTarget? = null
    private var ox = 0
    private var oy = 0
    private var allowTooltip = false

    /** Absolute clip rectangles pushed by `g:clip` (innermost last), for input hit tests. */
    private val clips = ArrayList<IntArray>()

    val table = LuaTable()

    init {
        method("rect") { a -> rect(a); LuaValue.NONE }
        method("outline") { a -> outline(a); LuaValue.NONE }
        method("gradient") { a -> gradient(a); LuaValue.NONE }
        method("line") { a -> line(a); LuaValue.NONE }
        // up to 2r+1 columns, each a sqrt/atan2 step and a fill
        method("arc", { a -> val r = a.optdouble(3, 0.0).coerceIn(0.0, MAX_ARC_RADIUS); (r * r / 8).toLong() }) { a -> arc(a); LuaValue.NONE }
        method("text", { a -> textCost(a.arg(1)) * a.arg(4).optionDouble("scale", 1.0).coerceIn(1.0, MAX_TEXT_SCALE).toLong() }) { a -> text(a) }
        method("textWidth", { a -> textCost(a.arg(1)) }) { a -> LuaValue.valueOf(target().textWidth(displayString(a.arg(1)))) }
        method("wrap", { a -> textCost(a.arg(1)) * 2 }) { a -> wrap(a) }
        method("texture") { a -> texture(a); LuaValue.NONE }
        method("sprite") { a -> sprite(a); LuaValue.NONE }
        method("item", { ITEM_COST }) { a -> item(a); LuaValue.NONE }
        method("itemTooltip", { ITEM_COST }) { a -> itemTooltip(a); LuaValue.NONE }
        method("tooltip", { a -> textCost(a.arg(1)) }) { a -> tooltip(a); LuaValue.NONE }
        method("clip") { a -> clip(a); LuaValue.NONE }
    }

    /** Runs [body] with `g` bound to [target] at origin ([x], [y]). */
    inline fun draw(target: DrawTarget, x: Int, y: Int, allowTooltip: Boolean, body: () -> Unit) {
        begin(target, x, y, allowTooltip)
        try {
            body()
        } finally {
            end()
        }
    }

    fun begin(target: DrawTarget, x: Int, y: Int, allowTooltip: Boolean) {
        this.target = target
        ox = x
        oy = y
        this.allowTooltip = allowTooltip
        clips.clear()
    }

    fun end() {
        target = null
        clips.clear()
    }

    /** The draw target of the render call in progress (for screen inputs); error outside rendering. */
    internal fun currentTarget(): DrawTarget = target()

    /** Innermost `g:clip` rectangle (absolute x0, y0, x1, y1), or `null`. */
    internal fun currentClip(): IntArray? = clips.lastOrNull()?.copyOf()

    /** `kiln.ui.tooltip`: only meaningful while a widget or screen renders. */
    fun tooltip(text: String) {
        val t = target ?: return
        if (allowTooltip) t.tooltip(text)
    }

    // ---- g: methods ---------------------------------------------------------------------------------------

    private fun rect(a: Varargs) {
        val x = a.checkdouble(1)
        val y = a.checkdouble(2)
        fill(x, y, x + a.checkdouble(3), y + a.checkdouble(4), LuaColor.of(a.arg(5)))
    }

    private fun outline(a: Varargs) {
        val x = a.checkdouble(1); val y = a.checkdouble(2); val w = a.checkdouble(3); val h = a.checkdouble(4)
        val c = LuaColor.of(a.arg(5))
        fill(x, y, x + w, y + 1, c)
        fill(x, y + h - 1, x + w, y + h, c)
        fill(x, y + 1, x + 1, y + h - 1, c)
        fill(x + w - 1, y + 1, x + w, y + h - 1, c)
    }

    private fun gradient(a: Varargs) {
        val t = target()
        val x = absX(a, 1)
        val y = absY(a, 2)
        val x1 = x + floor(a.checkdouble(3)).toInt()
        val y1 = y + floor(a.checkdouble(4)).toInt()
        if (x1 > x && y1 > y) t.gradient(x, y, x1, y1, LuaColor.of(a.arg(5)), LuaColor.of(a.arg(6)))
    }

    private fun line(a: Varargs) {
        target().line(
            (ox + a.checkdouble(1)).toFloat(), (oy + a.checkdouble(2)).toFloat(),
            (ox + a.checkdouble(3)).toFloat(), (oy + a.checkdouble(4)).toFloat(),
            a.optdouble(6, 1.0).coerceIn(MIN_LINE_THICKNESS, MAX_LINE_THICKNESS).toFloat(), LuaColor.of(a.arg(5)),
        )
    }

    private fun arc(a: Varargs) {
        val t = target()
        val radius = a.checkdouble(3).coerceAtMost(MAX_ARC_RADIUS)
        val color = LuaColor.of(a.arg(7))
        GaugeArc.runs(ox + a.checkdouble(1), oy + a.checkdouble(2), radius, a.checkdouble(4), a.checkdouble(5), a.checkdouble(6)) { x, y0, y1 ->
            t.fill(x, y0, x + 1, y1, color)
        }
    }

    /** `g:text`: the default font at scale 1 on whole pixels, scaled or resource-font text on the float path. */
    private fun text(a: Varargs): LuaValue {
        val t = target()
        val text = displayString(a.arg(1))
        val opts = a.arg(4)
        val align = opts.optionString("align", "left")
        val color = if (opts.istable()) LuaColor.of(opts.get("color")) else -1
        val shadow = opts.optionBoolean("shadow", true)
        val scale = opts.optionDouble("scale", 1.0)
        if (!(scale > 0.0 && scale <= MAX_TEXT_SCALE)) throw LuaError("text scale must be in (0, $MAX_TEXT_SCALE]")
        val font = if (opts.istable() && !opts.get("font").isnil()) extension.ownResource(opts.get("font").checkjstring(), "font") else null
        if (font != null) {
            // resource font (permission resources): always the float path
            val scaled = t.fontTextWidth(text, font) * scale
            val x = ox + a.checkdouble(2) - scaled * alignFactor(align)
            t.fontText(text, x.toFloat(), (oy + a.checkdouble(3)).toFloat(), color, shadow, scale.toFloat(), font)
            return LuaValue.valueOf(scaled)
        }
        val width = t.textWidth(text)
        if (scale == 1.0) {
            val x = absX(a, 2) - (width * alignFactor(align)).toInt()
            t.text(text, x, absY(a, 3), color, shadow)
            return LuaValue.valueOf(width)
        }
        val scaled = width * scale
        val x = ox + a.checkdouble(2) - scaled * alignFactor(align)
        t.scaledText(text, x.toFloat(), (oy + a.checkdouble(3)).toFloat(), color, shadow, scale.toFloat())
        return LuaValue.valueOf(scaled)
    }

    private fun wrap(a: Varargs): LuaValue {
        val lines = target().wrap(displayString(a.arg(1)), a.checkint(2).coerceIn(1, MAX_WRAP_WIDTH))
        val result = LuaTable()
        lines.take(MAX_LINES).forEachIndexed { i, line -> result.set(i + 1, line) }
        return result
    }

    private fun texture(a: Varargs) {
        val t = target()
        val raw = a.checkjstring(1)
        val id = if (':' in raw) raw else "${extension.id}:$raw"
        val size = t.textureSize(id) ?: throw LuaError("unknown texture '$raw'")
        val w = a.checkint(4)
        val h = a.checkint(5)
        val tw = a.optint(10, size[0])
        val th = a.optint(11, size[1])
        t.texture(
            id, absX(a, 2), absY(a, 3), w, h,
            a.optdouble(6, 0.0).toFloat(), a.optdouble(7, 0.0).toFloat(), a.optint(8, tw), a.optint(9, th), tw, th,
        )
    }

    private fun sprite(a: Varargs) {
        val t = target()
        val raw = a.checkjstring(1)
        val id = raw.removePrefix("minecraft:")
        if (id !in SPRITES) throw LuaError("sprite '$raw' is not allowed (see the sprite list in docs/lua-extensions.md)")
        val w = a.checkint(4)
        val h = a.checkint(5)
        if (w > 0 && h > 0 && w <= MAX_SPRITE_SIZE && h <= MAX_SPRITE_SIZE) {
            t.sprite("minecraft:$id", absX(a, 2), absY(a, 3), w, h)
        }
    }

    private fun item(a: Varargs) {
        val t = target()
        val x = absX(a, 2)
        val y = absY(a, 3)
        val opts = a.arg(4)
        var size = DEFAULT_ITEM_SIZE
        var count = 1
        var decorations = true
        if (opts.isnumber()) {
            size = opts.toint()
        } else if (opts.istable()) {
            size = opts.get("size").optint(DEFAULT_ITEM_SIZE)
            count = opts.get("count").optint(1)
            decorations = opts.optionBoolean("decorations", true)
        } else if (!opts.isnil()) {
            throw LuaError("item options must be a size or a table")
        }
        size = size.coerceIn(1, MAX_ITEM_SIZE)
        count = count.coerceIn(1, MAX_ITEM_COUNT)
        when (val item = a.arg(1)) {
            is ItemHandle -> t.itemStack(item.ref, x, y, size, count, decorations)
            else -> t.item(a.checkjstring(1), x, y, size, count, decorations && count > 1)
        }
    }

    private fun itemTooltip(a: Varargs) {
        val t = target()
        val ref = LuaItems.ref(a.arg(1))
        if (allowTooltip) t.itemTooltip(ref, absX(a, 2), absY(a, 3), lines(a.arg(4)))
    }

    private fun tooltip(a: Varargs) {
        val t = target()
        if (!allowTooltip) return
        val v = a.arg(1)
        if (v.istable()) t.tooltipLines(lines(v)) else t.tooltip(displayString(v))
    }

    private fun clip(a: Varargs) {
        val t = target()
        val x0 = absX(a, 1)
        val y0 = absY(a, 2)
        val x1 = x0 + floor(a.checkdouble(3)).toInt().coerceAtLeast(0)
        val y1 = y0 + floor(a.checkdouble(4)).toInt().coerceAtLeast(0)
        val fn = a.checkfunction(5)
        if (clips.size >= MAX_CLIP_DEPTH) throw LuaError("clips nested too deeply (limit $MAX_CLIP_DEPTH)")
        val rect = intersectWithOuterClip(x0, y0, x1, y1)
        t.pushClip(rect[0], rect[1], rect[2], rect[3])
        clips += rect
        try {
            fn.invoke(LuaValue.NONE)
        } finally {
            clips.removeAt(clips.size - 1)
            // the target may have been cleared by an error unwinding through end(); pop on the captured one
            t.popClip()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** The rectangle cut down to the innermost clip (never inverted: an empty intersection has zero size). */
    private fun intersectWithOuterClip(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val outer = clips.lastOrNull() ?: return intArrayOf(x0, y0, x1, y1)
        val left = maxOf(x0, outer[0])
        val top = maxOf(y0, outer[1])
        return intArrayOf(left, top, maxOf(left, minOf(x1, outer[2])), maxOf(top, minOf(y1, outer[3])))
    }

    private fun target(): DrawTarget = target ?: throw LuaError("g can only be used while drawing")

    /** Absolute pixel x of the relative coordinate argument [index]. */
    private fun absX(a: Varargs, index: Int): Int = ox + floor(a.checkdouble(index)).toInt()

    /** Absolute pixel y of the relative coordinate argument [index]. */
    private fun absY(a: Varargs, index: Int): Int = oy + floor(a.checkdouble(index)).toInt()

    /** Strings as they are (cut to [LuaExtension.MAX_TEXT]), anything else as its type name (no `__tostring`). */
    private fun displayString(v: LuaValue): String = (if (v.isstring()) v.tojstring() else v.typename()).take(LuaExtension.MAX_TEXT)

    private fun lines(v: LuaValue): List<String> {
        if (v.isnil()) return emptyList()
        if (v.isstring()) return listOf(displayString(v))
        val t = v.checktable()
        val result = ArrayList<String>()
        for (i in 1..minOf(t.length(), MAX_LINES)) result += displayString(t.get(i))
        return result
    }

    /** Part of the text width left of the anchor point. */
    private fun alignFactor(align: String): Double = when (align) {
        "center" -> 0.5
        "right" -> 1.0
        "left" -> 0.0
        else -> throw LuaError("invalid align '$align'")
    }

    private fun fill(x0: Double, y0: Double, x1: Double, y1: Double, color: Int) {
        val t = target()
        val ax = ox + floor(x0).toInt()
        val ay = oy + floor(y0).toInt()
        val bx = ox + floor(x1).toInt()
        val by = oy + floor(y1).toInt()
        if (bx > ax && by > ay) t.fill(ax, ay, bx, by, color)
    }

    /**
     * Registers `g:<name>` (the implicit `self` argument is dropped). Every call costs [DRAW_COST] instructions plus
     * what [cost] says about its arguments: the work happens in Java (fills, glyphs, items), which the instruction
     * hook would not see, so a few cheap-looking calls in a loop could otherwise take seconds per frame.
     */
    private fun method(name: String, cost: (Varargs) -> Long = { 0 }, body: (Varargs) -> Varargs) {
        table.set(name, object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val a = args.withoutSelf(table)
                LuaExecution.chargeInstructions(DRAW_COST + cost(a))
                return body(a)
            }
        })
    }

    private fun textCost(v: LuaValue): Long = if (v.isstring()) minOf(v.rawlen(), LuaExtension.MAX_TEXT).toLong() / 4 else 0

    companion object {
        const val MAX_ARC_RADIUS = 128.0

        /** Instructions every `g:` call costs (about the Java work of a fill). */
        const val DRAW_COST = 8L

        /** Extra instructions of drawing an item (model render). */
        const val ITEM_COST = 64L
        const val MAX_TEXT_SCALE = 8.0
        const val MAX_LINES = 64
        const val MAX_CLIP_DEPTH = 16
        const val MAX_SPRITE_SIZE = 4_096
        const val MAX_ITEM_COUNT = 1_000_000_000
        private const val DEFAULT_ITEM_SIZE = 16
        private const val MAX_ITEM_SIZE = 256
        private const val MAX_WRAP_WIDTH = 8_192
        private const val MIN_LINE_THICKNESS = 0.5
        private const val MAX_LINE_THICKNESS = 32.0

        /** Vanilla GUI sprites `g:sprite` may draw (ids without the `minecraft:` namespace). */
        val SPRITES: Set<String> = linkedSetOf(
            "widget/button", "widget/button_highlighted", "widget/button_disabled",
            "widget/checkbox", "widget/checkbox_highlighted", "widget/checkbox_selected", "widget/checkbox_selected_highlighted",
            "widget/slider", "widget/slider_highlighted", "widget/slider_handle", "widget/slider_handle_highlighted",
            "widget/scroller", "widget/scroller_background",
            "widget/text_field", "widget/text_field_highlighted",
            "widget/tab", "widget/tab_highlighted", "widget/tab_selected", "widget/tab_selected_highlighted",
            "widget/page_forward", "widget/page_forward_highlighted", "widget/page_backward", "widget/page_backward_highlighted",
            "widget/locked_button", "widget/locked_button_highlighted", "widget/locked_button_disabled",
            "widget/unlocked_button", "widget/unlocked_button_highlighted", "widget/unlocked_button_disabled",
            "container/slot", "container/slot_highlight_back", "container/slot_highlight_front",
            "popup/background", "icon/search", "icon/checkmark",
        )
    }
}
