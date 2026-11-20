package dev.kiln.example.server.demos.warps

data class WarpDto(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val own: Boolean,
    val favorite: Boolean,
)
