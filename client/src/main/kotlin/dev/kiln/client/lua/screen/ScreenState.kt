package dev.kiln.client.lua.screen

/** Lifecycle of a [LuaScreenSession]: laid out once (`init`), then open until removed or disposed. */
enum class ScreenState { NEW, OPEN, CLOSED }
