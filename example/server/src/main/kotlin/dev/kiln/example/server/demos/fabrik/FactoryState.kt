package dev.kiln.example.server.demos.fabrik

data class FactoryState(
    val supply: Int,
    val used: Int,
    val maxPriority: Int,
    val types: List<MachineTypeDto>,
    val machines: List<MachineDto>,
)
