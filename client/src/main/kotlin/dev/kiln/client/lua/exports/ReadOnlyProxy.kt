package dev.kiln.client.lua.exports

import dev.kiln.client.lua.sandbox.LuaExecution
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** See [LuaExports]. The proxy itself stores nothing; its metatable is locked and rejects writes. */
class ReadOnlyProxy internal constructor(internal val target: LuaTable, internal val exports: LuaExports) : LuaTable() {
    init {
        super.setmetatable(LOCKED_METATABLE)
    }

    private fun denied(): Nothing = throw LuaError("attempt to modify a read-only table (exported by '${exports.owner.id}')")

    // outside a runtime call (host code reading a definition table that is a proxy) no metamethod may run: it would
    // run Lua without budget and without principal
    override fun rawget(key: Int): LuaValue = exports.wrap(if (LuaExecution.active) target.get(key) else target.rawget(key))
    override fun rawget(key: LuaValue): LuaValue = exports.unwrap(key).let { k -> exports.wrap(if (LuaExecution.active) target.get(k) else target.rawget(k)) }
    override fun get(key: Int): LuaValue = rawget(key)
    override fun get(key: LuaValue): LuaValue = rawget(key)

    override fun rawset(key: Int, value: LuaValue) = denied()
    override fun rawset(key: LuaValue, value: LuaValue) = denied()
    override fun set(key: Int, value: LuaValue) = denied()
    override fun set(key: LuaValue, value: LuaValue) = denied()
    override fun setmetatable(metatable: LuaValue?): LuaValue = denied()
    override fun insert(pos: Int, value: LuaValue) = denied()
    override fun remove(pos: Int): LuaValue = denied()
    override fun sort(comparator: LuaValue) = denied()

    override fun next(key: LuaValue): Varargs {
        val n = target.next(exports.unwrap(key))
        val k = n.arg1()
        if (k.isnil()) return LuaValue.NIL
        return LuaValue.varargsOf(exports.wrap(k), exports.wrap(n.arg(2)))
    }

    override fun inext(key: LuaValue): Varargs {
        val n = target.inext(key)
        if (n.arg1().isnil()) return LuaValue.NONE
        return LuaValue.varargsOf(n.arg1(), exports.wrap(n.arg(2)))
    }

    override fun rawlen(): Int = target.rawlen()
    override fun length(): Int = target.length()
    override fun len(): LuaValue = target.len()

    override fun tojstring(): String = "table (read-only)"

    private companion object {
        /** `__metatable` hides it (and blocks `setmetatable`), `__newindex` rejects new keys. */
        val LOCKED_METATABLE: LuaTable = LuaTable().apply {
            set("__metatable", LuaExports.LOCK)
            set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = throw LuaError("attempt to modify a read-only table")
            })
        }
    }
}
