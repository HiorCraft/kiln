package dev.kiln.example.server

/** Factory state for the Lua HUD overlay on `kiln_example:machine` (server -> client only). */
data class MachineState(val running: Boolean, val energy: Float, val output: Int)
