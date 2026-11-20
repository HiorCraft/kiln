package dev.kiln.client.lua.screen

import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.luaFunction
import dev.kiln.client.lua.requireFunctionOrNil
import dev.kiln.client.lua.withoutSelf
import dev.kiln.client.screen.NumericInput
import dev.kiln.protocol.element.NumericRange
import dev.kiln.protocol.ProtocolLimits
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import kotlin.math.floor

/** One `s:textInput` field of [session]: the native box plus the Lua handle table `field`. */
class TextInput internal constructor(private val session: LuaScreenSession, val id: String, val box: TextBox) {
    val field = LuaTable()
    private var frame = 0
    private var lastOptsValue: String? = null
    private var onChange: LuaValue = LuaValue.NIL
    private var numeric: NumericRange? = null
    internal var enabled = true
        private set
    private var rect = IntArray(4)
    private var clip: IntArray? = null

    init {
        field.rawset("id", LuaValue.valueOf(id))
        field.rawset("focus", luaFunction { session.setFocus(this); LuaValue.NONE })
        field.rawset("blur", luaFunction { if (session.focused === this) session.setFocus(null); LuaValue.NONE })
        field.rawset("setValue", luaFunction { a ->
            box.setValue(a.withoutSelf(field).arg1().checkjstring())
            sync(box.value)
            LuaValue.NONE
        })
    }

    internal fun declaredIn(frame: Int): Boolean = this.frame == frame

    /** `s:textInput(id, opts)` in the frame being rendered: configures, places and draws the box. */
    internal fun declare(opts: LuaTable, fresh: Boolean, target: DrawTarget, mouseX: Int, mouseY: Int) {
        val ax = session.x + floor(opts.rawget("x").checkdouble()).toInt()
        val ay = session.y + floor(opts.rawget("y").checkdouble()).toInt()
        val w = opts.rawget("w").checkint()
        val h = opts.rawget("h").optint(LuaScreenSession.DEFAULT_INPUT_HEIGHT)
        if (w !in 1..LuaScreenSession.MAX_SIZE || h !in 1..LuaScreenSession.MAX_SIZE) throw LuaError("text input '$id': w/h must be 1..${LuaScreenSession.MAX_SIZE}")
        configure(opts)
        // size first: a vanilla EditBox scrolls the text for its current width when the value is set (width 0 on a
        // fresh box would scroll a preset value out of view)
        box.place(ax, ay, w, h)
        applyScriptValue(opts, fresh)
        if (!enabled && session.focused === this) session.setFocus(null)
        sync(box.value)
        rect = intArrayOf(ax, ay, ax + w, ay + h)
        clip = session.graphics.currentClip()
        box.draw(target, mouseX, mouseY)
        frame = session.frame
    }

    private fun configure(opts: LuaTable) {
        val maxLength = opts.rawget("maxLength").optint(LuaScreenSession.DEFAULT_MAX_LENGTH)
        if (maxLength !in 1..ProtocolLimits.MAX_TEXT_FIELD_LENGTH) throw LuaError("text input '$id': maxLength must be 1..${ProtocolLimits.MAX_TEXT_FIELD_LENGTH}")
        numeric = numericRange(opts.rawget("numeric"))
        enabled = opts.rawget("enabled").let { it.isnil() || it.toboolean() }
        onChange = opts.rawget("onChange").also { requireFunctionOrNil(it, "text input '$id'", "onChange") }
        box.configure(maxLength, opts.rawget("placeholder").optjstring("").take(LuaExtension.MAX_TEXT), numeric, enabled)
    }

    /** `opts.value`: the initial value of a fresh box; later only a change of it is applied (never as an edit). */
    private fun applyScriptValue(opts: LuaTable, fresh: Boolean) {
        val value = opts.rawget("value").let { if (it.isnil()) null else it.checkjstring() }
        if (fresh) {
            box.setValue(value ?: "")
            lastOptsValue = value
            if (opts.rawget("focus").toboolean()) session.setFocus(this)
        } else if (value != null && value != lastOptsValue) {
            lastOptsValue = value
            if (box.value != value) box.setValue(value)
        }
    }

    internal fun hit(mx: Double, my: Double): Boolean {
        if (!enabled || !declaredIn(session.frame)) return false
        if (!rect.contains(mx, my)) return false
        return clip?.contains(mx, my) ?: true
    }

    /** The player edited the box. */
    internal fun edited(value: String) {
        sync(value)
        val change = onChange
        if (field.rawget("valid").toboolean() && change.isfunction()) session.extension.invoke(change, LuaValue.varargsOf(field, LuaValue.valueOf(value)))
    }

    private fun sync(value: String) {
        field.rawset("value", LuaValue.valueOf(value))
        val range = numeric
        field.rawset("valid", LuaValue.valueOf(range == null || NumericInput.reportable(value, range)))
        field.rawset("focused", LuaValue.valueOf(session.focused === this))
    }

    internal fun focusChanged() {
        field.rawset("focused", LuaValue.valueOf(session.focused === this))
    }

    /** Whether ([x], [y]) lies in the rectangle `x0, y0, x1, y1` (x1/y1 exclusive). */
    private fun IntArray.contains(x: Double, y: Double): Boolean = x >= this[0] && y >= this[1] && x < this[2] && y < this[3]

    /** `opts.numeric`: `nil`/`false` = none, `true` = any integer, or a table `{min, max, integer}`. */
    private fun numericRange(v: LuaValue): NumericRange? {
        if (v.isnil() || v == LuaValue.FALSE) return null
        if (v == LuaValue.TRUE) return NumericRange(-ProtocolLimits.MAX_NUMERIC_VALUE, ProtocolLimits.MAX_NUMERIC_VALUE, true)
        val t = v.checktable()
        val min = t.rawget("min").optdouble(-ProtocolLimits.MAX_NUMERIC_VALUE)
        val max = t.rawget("max").optdouble(ProtocolLimits.MAX_NUMERIC_VALUE)
        if (!(min <= max)) throw LuaError("numeric: min must be <= max")
        return NumericRange(min, max, t.rawget("integer").let { it.isnil() || it.toboolean() })
    }
}
