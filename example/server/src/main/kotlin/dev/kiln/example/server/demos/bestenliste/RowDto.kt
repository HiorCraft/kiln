package dev.kiln.example.server.demos.bestenliste

data class RowDto(
    val rank: Int,
    val name: String,
    val value: Long,
    /** Gain since the previous snapshot (trend column). */
    val gain: Long,
    val self: Boolean,
)
