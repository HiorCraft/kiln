package dev.kiln.client.lua

import dev.kiln.client.lua.graphics.DrawTarget

/** Records draw calls. */
class RecordingTarget : DrawTarget {
    val calls = ArrayList<String>()
    var fills = 0
    override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { fills++ }
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) { calls += "line" }
    override fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean) { calls += "text:$text@$x,$y" }
    override fun textWidth(text: String): Int = text.length * 6
    override fun textureSize(id: String): IntArray? = if (id == "x:textures/icon.png") intArrayOf(16, 16) else null
    override fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int) {
        calls += "texture:$id@$x,$y ${w}x$h r=${regionWidth}x$regionHeight t=${textureWidth}x$textureHeight"
    }
    override fun item(itemId: String, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { calls += "item:$itemId@$x,$y" }
    override fun tooltip(text: String) { calls += "tooltip:$text" }
}
