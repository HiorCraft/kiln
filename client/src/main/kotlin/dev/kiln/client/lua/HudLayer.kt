package dev.kiln.client.lua

import org.luaj.vm2.LuaValue

/** A HUD layer defined with `kiln.hud.layer`: size, screen anchor and render callback. */
class HudLayer(val id: String, val anchor: String, val width: Int, val height: Int, val render: LuaValue)
