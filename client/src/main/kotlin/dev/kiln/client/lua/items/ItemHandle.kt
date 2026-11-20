package dev.kiln.client.lua.items

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Opaque Lua handle of a decoded item ([ItemRef]). Lua sees a userdata whose metatable is locked
 * (`getmetatable(h) == "item"`) and offers the read-only fields `h.id`, `h.name`, `h.maxStack`; nothing else of the
 * stack is reachable. Handles are immutable and may be shared with other extensions.
 */
class ItemHandle internal constructor(val ref: ItemRef) : LuaUserdata(ref, METATABLE) {
    override fun setmetatable(metatable: LuaValue?): LuaValue = throw LuaError("cannot change the metatable of an item handle")

    override fun tojstring(): String = "item(${ref.id})"

    companion object {
        private val METATABLE: LuaTable = LuaTable().apply {
            set("__metatable", "item")
            set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val ref = (args.arg1() as? ItemHandle)?.ref ?: return LuaValue.NIL
                    return when (args.arg(2).optjstring("")) {
                        "id" -> LuaValue.valueOf(ref.id)
                        "name" -> LuaValue.valueOf(ref.name)
                        "maxStack" -> LuaValue.valueOf(ref.maxStackSize)
                        else -> LuaValue.NIL
                    }
                }
            })
            set("__tostring", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = LuaValue.valueOf(args.arg1().tojstring())
            })
        }
    }
}
