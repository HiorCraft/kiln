package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * `string.format`: the result is bounded **before** it is built: the format text, every argument (strings up to
 * twice for `%q`) and the widths of the conversions (at most 99 per conversion).
 */
internal class BoundedFormat(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val format = args.checkstring(1)
        var bound = format.m_length.toLong() * 50
        for (i in 2..args.narg()) {
            val a = args.arg(i)
            bound += if (a is LuaString) a.m_length * 2L + 2 else 64L
        }
        checkStringLength(limits, bound)
        LuaExecution.chargeBytes(bound)
        return delegate.invoke(args)
    }
}
