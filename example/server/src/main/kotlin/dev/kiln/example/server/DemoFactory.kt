package dev.kiln.example.server

/** Per-player factory state; the screen content lambda simply reads it on every refresh. */
internal class DemoFactory {
    var running = false
    var energy = 0f
    var counter = 0
    var machineName = "Maschine 1"
    var input = 32
    var output = 0

    val energyPercent: Int get() = (energy * 100).toInt()

    /** One production step: the energy charges up, and every full charge turns one input into one output. */
    fun advance() {
        energy += ENERGY_PER_STEP
        if (energy < 1f) return
        energy = 0f
        if (input > 0) {
            input--
            output++
        }
    }

    private companion object {
        /** Energy gained per (one-second) production step; a full charge is 1. */
        const val ENERGY_PER_STEP = 0.1f
    }
}
