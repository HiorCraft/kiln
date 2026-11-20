package dev.kiln.example.server.demos.profil

import dev.kiln.example.server.demos.DemoResult

/** Result of `save`: [settings] = the saved settings to apply (only when ok). */
data class SaveOutcome(val result: DemoResult, val settings: Settings? = null)
