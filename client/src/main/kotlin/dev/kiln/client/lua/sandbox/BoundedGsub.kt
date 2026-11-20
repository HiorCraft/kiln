package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** `string.gsub`: the worst-case result of a string replacement is checked before, the real one after the call. */
internal class BoundedGsub(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val s = args.checkstring(1)
        checkStringLength(limits, s.m_length.toLong())
        val repl = args.arg(3)
        if (repl is LuaString || repl.isnumber()) {
            // worst case: every position (and the end) matches the empty pattern
            val max = if (args.isnil(4)) s.m_length + 1L else minOf(args.checkint(4).toLong().coerceAtLeast(0), s.m_length + 1L)
            checkStringLength(limits, s.m_length + max * repl.tostring().checkstring().m_length)
        }
        val result = delegate.invoke(args)
        val resultLength = result.arg1().checkstring().m_length.toLong()
        checkStringLength(limits, resultLength)
        LuaExecution.chargeBytes(resultLength)
        return result
    }
}
