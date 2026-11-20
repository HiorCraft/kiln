package dev.kiln.client.lua.sandbox

/** Who called the host function that is running now ([LuaExecution.caller]). */
sealed interface LuaCaller {
    /** No Lua function on the stack: the host called the function directly (e.g. as a callback). */
    data object Host : LuaCaller

    /** The innermost Lua function on the stack belongs to [runtime]. */
    data class Script(val runtime: LuaRuntime) : LuaCaller

    /** A Lua function whose owner is unknown (must not happen; treated as "deny"). */
    data object Unknown : LuaCaller

    /** Not called directly by Lua code: a metamethod or a library function in between (denied for gated functions). */
    data object Indirect : LuaCaller
}
