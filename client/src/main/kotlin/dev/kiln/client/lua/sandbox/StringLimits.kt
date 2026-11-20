package dev.kiln.client.lua.sandbox

import org.luaj.vm2.LuaError

/** Refuses a string of [length] bytes above [LuaLimits.maxStringBytes] (before it is built). */
internal fun checkStringLength(limits: LuaLimits, length: Long) {
    if (length > limits.maxStringBytes) throw LuaError("string too long (limit ${limits.maxStringBytes} bytes)")
}
