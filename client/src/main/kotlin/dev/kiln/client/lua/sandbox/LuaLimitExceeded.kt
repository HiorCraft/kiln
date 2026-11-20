package dev.kiln.client.lua.sandbox

/**
 * Thrown when a script exceeds a hard limit (instructions, memory). Deliberately an [Error], not an [Exception]:
 * LuaJ's `pcall` catches `LuaError` and every `Exception`, but not `Error`, so a script cannot swallow it and keep
 * running. LuaJ's interpreter loop (`LuaClosure.execute`) only wraps `Exception`s, so it travels unchanged up to the
 * runtime's call boundary ([LuaRuntime.call]), which unloads the extension.
 */
class LuaLimitExceeded(message: String) : Error(message, null, false, false)
