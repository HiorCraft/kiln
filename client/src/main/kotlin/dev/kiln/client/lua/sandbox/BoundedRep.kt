package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** `string.rep`: the result length is checked and charged before it is built. */
internal class BoundedRep(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val s = args.checkstring(1)
        val n = args.checkint(2)
        if (n <= 0 || s.m_length == 0) return LuaValue.EMPTYSTRING
        checkStringLength(limits, s.m_length.toLong() * n)
        LuaExecution.chargeBytes(s.m_length.toLong() * n)
        return delegate.invoke(args)
    }
}
