package dev.kiln.example.server.demos.fabrik

data class MachineDto(
    val id: Int,
    val name: String,
    val type: String,
    val running: Boolean,
    val priority: Int,
    val recipe: String,
    /** 0..1 progress of the current item. */
    val progress: Double,
    /** 0..1 share of the needed power the machine gets. */
    val power: Double,
    /** Power the machine needs while running. */
    val demand: Int,
    val produced: Long,
    val status: String,
    val statusLabel: String,
)
