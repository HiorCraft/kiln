package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** `table.unpack` with at most [LuaLimits.maxUnpack] results. */
internal class BoundedUnpack(private val limits: LuaLimits) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val t = args.checktable(1)
        val i = args.optint(2, 1)
        val j = if (args.isnoneornil(3)) t.length() else args.checkint(3)
        if (i > j) return LuaValue.NONE
        if (j.toLong() - i + 1 > limits.maxUnpack) throw LuaError("too many results to unpack")
        return t.unpack(i, j)
    }
}
