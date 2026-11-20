package dev.kiln.client.lua.sandbox

/** Result of [LuaMemory.estimate]: bytes and objects counted until the walk ended, [exceeded] if a limit was hit. */
data class MemoryEstimate(val bytes: Long, val objects: Int, val exceeded: Boolean)
