package dev.kiln.client.entity.model

/** A server animation playing on an entity (`PlayAnimation`), started at [start] seconds of the client clock. */
data class ActiveAnimation(val name: String, val mode: Int, val speed: Float, val start: Double)
