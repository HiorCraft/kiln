package dev.kiln.example.server.demos.warps

data class WarpState(
    val cooldownMillis: Long,
    /** Remaining cooldown when the state was built (0 = ready). */
    val cooldownLeft: Long,
    val maxOwn: Int,
    val nameMin: Int,
    val nameMax: Int,
    val icons: List<IconDto>,
    val warps: List<WarpDto>,
)
