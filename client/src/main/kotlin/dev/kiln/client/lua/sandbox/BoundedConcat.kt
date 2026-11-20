package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** `table.concat`: the result length is checked and charged before it is built. */
internal class BoundedConcat(private val limits: LuaLimits) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val list = args.checktable(1)
        val sep = if (args.isnoneornil(2)) LuaValue.EMPTYSTRING else args.checkstring(2)
        val i = args.optint(3, 1)
        val j = if (args.isnoneornil(4)) list.length() else args.checkint(4)
        if (i > j) return LuaValue.EMPTYSTRING
        var total = 0L
        for (k in i..j) {
            val v = list.get(k)
            total += if (v is LuaString) v.m_length.toLong() else if (v.isnumber()) 32L else throw LuaError("invalid value (at index $k) in table for 'concat'")
            total += sep.m_length
            checkStringLength(limits, total)
        }
        LuaExecution.chargeBytes(total)
        return list.concat(sep, i, j)
    }
}
