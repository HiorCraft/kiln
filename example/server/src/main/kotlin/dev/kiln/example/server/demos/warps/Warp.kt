package dev.kiln.example.server.demos.warps

import java.util.UUID

data class Warp(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val location: WarpLocation,
    /** `null` for public warps. */
    val owner: UUID? = null,
)
