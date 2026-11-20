package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaClosure

/** How the host function that runs now was reached (see [SandboxHook.directCall]). */
sealed interface CallOrigin {
    /** No Lua frame: the host called it (e.g. as a registered callback). */
    data object FromHost : CallOrigin

    /** A `CALL` instruction of [closure] calls it, directly or as `pcall(f, ...)`. */
    data class Direct(val closure: LuaClosure) : CallOrigin

    /** Anything else: a metamethod (`__index`, `__tostring`, `__concat`, `__eq`, `__call`, ...) or a library function. */
    data object Indirect : CallOrigin
}
