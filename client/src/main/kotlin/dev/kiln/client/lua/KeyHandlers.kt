package dev.kiln.client.lua

import org.luaj.vm2.LuaValue

/** `press` / `release` callbacks of one keybind (each may be `nil`). */
internal class KeyHandlers(val press: LuaValue, val release: LuaValue)
