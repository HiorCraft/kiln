package dev.kiln.client.lua.exports

import dev.kiln.client.lua.LuaExtension
import org.luaj.vm2.lib.VarArgFunction

/** A permission-gated API function of [owner] (`kiln.net.channel`, `ch.send`, `ui.toast`, ...), see [LuaExtension.checkCaller]. */
abstract class GatedFunction internal constructor(internal val owner: LuaExtension) : VarArgFunction()
