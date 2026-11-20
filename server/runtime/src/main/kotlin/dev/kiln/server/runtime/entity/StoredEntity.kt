package dev.kiln.server.runtime.entity

import java.util.UUID

/** A custom entity as stored in its chunk while it is not loaded. */
data class StoredEntity(
    val type: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val health: Double, val state: String, val id: UUID,
)
