package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** A table that cannot be written by Lua or LuaJ library code; filled only through [init]. */
internal open class ReadOnlyTable : LuaTable() {
    fun init(key: String, value: LuaValue) {
        super.rawset(LuaValue.valueOf(key), value)
    }

    override fun rawset(key: LuaValue, value: LuaValue) = throw LuaError("attempt to modify a read-only table")
    override fun rawset(key: Int, value: LuaValue) = throw LuaError("attempt to modify a read-only table")
    override fun setmetatable(metatable: LuaValue?): LuaValue = throw LuaError("attempt to modify a read-only table")
}
