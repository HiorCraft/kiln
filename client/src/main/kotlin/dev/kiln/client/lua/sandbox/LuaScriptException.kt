package dev.kiln.client.lua.sandbox

/** A script failed or exceeded a limit; the extension must be unloaded. [message] is safe to show to the player. */
class LuaScriptException(message: String, cause: Throwable? = null) : Exception(message, cause)
