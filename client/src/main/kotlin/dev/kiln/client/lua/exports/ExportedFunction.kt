package dev.kiln.client.lua.exports

import dev.kiln.client.lua.sandbox.LuaExecution
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** See [LuaExports]. */
class ExportedFunction internal constructor(internal val target: LuaValue, internal val exports: LuaExports) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        if (!exports.owner.alive) throw LuaError("extension '${exports.owner.id}' is unloaded")
        // deny by default: a view without holder (kiln.readonly) runs gated functions for nobody
        LuaExecution.pushHolder(exports.holder ?: LuaExecution.UNKNOWN_HOLDER)
        try {
            return adoptAll(target.invoke(adoptAll(args, arguments = true)), arguments = false)
        } finally {
            LuaExecution.popHolder()
        }
    }

    /** Proxies of the exporter's own tables in [values] become the real tables again ([arguments]); see [LuaExports.adopt]. */
    private fun adoptAll(values: Varargs, arguments: Boolean): Varargs {
        val n = values.narg()
        var changed: Array<LuaValue>? = null
        for (i in 1..n) {
            val v = values.arg(i)
            val u = exports.adopt(if (arguments) exports.unwrap(v) else v)
            if (u !== v) {
                if (changed == null) changed = Array(n) { values.arg(it + 1) }
                changed[i - 1] = u
            }
        }
        return if (changed == null) values else LuaValue.varargsOf(changed)
    }

    /** True if this wrapper (or a wrapper it wraps) wraps [function]. */
    internal fun wraps(function: LuaValue): Boolean {
        var v: LuaValue = this
        while (v is ExportedFunction) {
            v = v.target
            if (v === function) return true
        }
        return false
    }

    override fun tojstring(): String = "function (exported by ${exports.owner.id})"
}
