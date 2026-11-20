package dev.kiln.client.lua

import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.items.ItemRef

/** Counts draw calls (cheap, for benchmarks) and records a few for assertions. */
class CountingTarget : DrawTarget {
    var fills = 0
    var texts = 0
    var items = 0
    var clips = 0
    val cursors = ArrayList<String>()
    val tooltips = ArrayList<String>()
    val fonts = ArrayList<String>()
    override fun fontText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, font: String) { texts++; fonts += font }
    override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { fills++ }
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {}
    override fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean) { texts++ }
    override fun textWidth(text: String): Int = text.length * 6
    override fun textureSize(id: String): IntArray? = null
    override fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int) {}
    override fun item(itemId: String, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { items++ }
    override fun itemStack(ref: ItemRef, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { items++ }
    override fun tooltip(text: String) { tooltips += text }
    override fun itemTooltip(ref: ItemRef, x: Int, y: Int, extra: List<String>) { tooltips += "item:${ref.id}" }
    override fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int) { clips++ }
    override fun cursor(name: String) { cursors += name }
}
