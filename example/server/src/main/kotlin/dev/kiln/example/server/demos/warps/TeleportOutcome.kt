package dev.kiln.example.server.demos.warps

import dev.kiln.example.server.demos.DemoResult

/** Result of `teleport`: the service teleports to [location] (only set when allowed). */
data class TeleportOutcome(val result: DemoResult, val location: WarpLocation? = null, val warp: Warp? = null)
