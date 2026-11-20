package dev.kiln.client.lua

import dev.kiln.client.lua.graphics.DrawTarget
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

/** One `Custom` element rendered by a Lua widget. [self] survives `UpdateScreen`s (kept per element id). */
class WidgetInstance internal constructor(val extension: LuaExtension, private val def: LuaTable) {
    val self = LuaTable()
    private var propsSource: Map<String, String>? = null
    private var props: LuaTable = LuaTable()

    /** The widget table `w` handed to the callbacks; [props] is rebuilt only when the properties changed. */
    private fun widgetTable(width: Int, height: Int, properties: Map<String, String>, hovered: Boolean, mouseX: Double, mouseY: Double): LuaTable {
        if (propsSource != properties) {
            propsSource = HashMap(properties)
            props = propsTable(properties)
        }
        val w = LuaTable()
        w.set("width", width)
        w.set("height", height)
        w.set("props", props)
        w.set("hovered", LuaValue.valueOf(hovered))
        w.set("mouseX", mouseX)
        w.set("mouseY", mouseY)
        return w
    }

    /** `self, w, mx, my, last` of the pointer callbacks `press`, `release` and `scroll`. */
    private fun pointerArgs(w: LuaTable, mx: Double, my: Double, last: LuaValue): Varargs =
        LuaValue.varargsOf(arrayOf(self, w, LuaValue.valueOf(mx), LuaValue.valueOf(my), last))

    /** Draws the widget at ([x], [y]) with size [width] x [height]; mouse coordinates are absolute. */
    fun render(target: DrawTarget, x: Int, y: Int, width: Int, height: Int, properties: Map<String, String>, hovered: Boolean, mouseX: Double, mouseY: Double) {
        val render = def.rawget("render")
        if (!render.isfunction()) return
        val w = widgetTable(width, height, properties, hovered, mouseX - x, mouseY - y)
        extension.graphics.draw(target, x, y, allowTooltip = true) {
            extension.invoke(render, LuaValue.varargsOf(self, extension.graphics.table, w))
        }
    }

    /** Mouse button pressed inside the widget: `press` then `click`; returns `true` to report a `ScreenClick`. */
    fun mouseClicked(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, button: Int): Boolean {
        val w = widgetTable(width, height, properties, true, mx, my)
        val press = def.rawget("press")
        if (press.isfunction()) extension.invoke(press, pointerArgs(w, mx, my, LuaValue.valueOf(button)))
        val click = def.rawget("click")
        if (!click.isfunction()) return true
        val result = extension.invoke(click, LuaValue.varargsOf(self, w, LuaValue.valueOf(button))) ?: return false
        return result.arg1().toboolean()
    }

    /** Mouse button released after a press inside this widget (coordinates relative, may be outside). */
    fun mouseReleased(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, button: Int) {
        val release = def.rawget("release")
        if (!release.isfunction()) return
        val inside = mx >= 0 && my >= 0 && mx < width && my < height
        val w = widgetTable(width, height, properties, inside, mx, my)
        extension.invoke(release, pointerArgs(w, mx, my, LuaValue.valueOf(button)))
    }

    /** Wheel over the widget; `true` if the script consumed it. */
    fun mouseScrolled(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, amount: Double): Boolean {
        val scroll = def.rawget("scroll")
        if (!scroll.isfunction()) return false
        val w = widgetTable(width, height, properties, true, mx, my)
        val result = extension.invoke(scroll, pointerArgs(w, mx, my, LuaValue.valueOf(amount))) ?: return false
        return result.arg1().toboolean()
    }
}

private fun propsTable(properties: Map<String, String>): LuaTable {
    val table = LuaTable()
    for ((key, value) in properties) table.set(key, value)
    return table
}
