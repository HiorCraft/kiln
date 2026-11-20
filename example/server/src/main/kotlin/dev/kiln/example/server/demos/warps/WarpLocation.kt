package dev.kiln.example.server.demos.warps

/** Where a warp leads; [surface] = y is taken from the highest block at x/z when teleporting. */
data class WarpLocation(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f, val surface: Boolean = false)
