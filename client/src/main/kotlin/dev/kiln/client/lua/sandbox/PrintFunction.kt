package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** Characters of one `print` line; the rest is cut off. */
private const val MAX_PRINT_LENGTH = 1_000

/** Lua's `print`: plain values joined by tabs, cut to [MAX_PRINT_LENGTH] characters, handed to [sink]. */
internal class PrintFunction(private val sink: (String) -> Unit) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        val text = StringBuilder()
        for (i in 1..args.narg()) {
            if (i > 1) text.append('\t')
            val value = args.arg(i)
            // plain tostring: no __tostring metamethod, so print can never run unbudgeted Lua code
            text.append(if (value.isstring()) value.tojstring() else value.typename())
            if (text.length > MAX_PRINT_LENGTH) break
        }
        sink(text.take(MAX_PRINT_LENGTH).toString())
        return LuaValue.NONE
    }
}
