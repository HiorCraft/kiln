package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** A function returning a copy of its string argument (`upper`, `lower`, `reverse`). */
internal class ChargedCopy(private val delegate: LuaValue) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        LuaExecution.chargeBytes(args.checkstring(1).m_length.toLong())
        return delegate.invoke(args)
    }
}
