package dev.kiln.example.server.demos.profil

data class ProfileState(
    val name: String,
    val settings: Settings,
    val defaults: Settings,
    val colors: List<ColorDto>,
    val filters: List<OptionDto>,
    val nickMin: Int,
    val nickMax: Int,
    val statusMax: Int,
    val volumeStep: Int,
)
