package dev.kiln.server.runtime.furniture

import java.util.UUID

/** One placed furniture as stored in its chunk. */
data class StoredFurniture(val type: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val id: UUID)
