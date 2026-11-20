package dev.kiln.client.lua.graphics

import org.luaj.vm2.LuaValue

/** ARGB colors as Lua sees them: numbers such as `0xFF2A2F2C`. */
object LuaColor {
    /** Lua number -> ARGB int (numbers above 2^31 such as `0xFF2A2F2C` wrap); [default] for anything else. */
    fun of(value: LuaValue, default: Int = -1): Int = if (value.isnumber()) value.todouble().toLong().toInt() else default

    /** ARGB int -> the unsigned number Lua scripts write (`0xFF2A2F2C`). */
    fun unsigned(argb: Int): Double = (argb.toLong() and 0xFFFFFFFFL).toDouble()

    /** Per-channel linear interpolation from [a] to [b]; [t] is clamped to 0..1. */
    fun lerp(a: Int, b: Int, t: Double): Int {
        val f = t.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * f + 0.5).toInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }
}
