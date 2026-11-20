package dev.kiln.example.server.demos.fabrik

enum class MachineStatus(val label: String) {
    RUNNING("Läuft"),
    LOW_POWER("Wenig Strom"),
    NO_POWER("Kein Strom"),
    OFF("Aus"),
}
