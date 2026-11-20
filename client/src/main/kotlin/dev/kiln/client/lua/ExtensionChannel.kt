package dev.kiln.client.lua

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** One `kiln.net.channel`: its Lua table and the handlers registered with `ch:on`. */
internal class ExtensionChannel {
    val handlers = ArrayList<LuaValue>()
    val table = LuaTable()
}
