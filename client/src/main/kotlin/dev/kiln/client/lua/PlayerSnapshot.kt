package dev.kiln.client.lua

/** Read-only snapshot of the local player handed to Lua (`kiln.player()`). */
data class PlayerSnapshot(val name: String, val x: Double, val y: Double, val z: Double)
