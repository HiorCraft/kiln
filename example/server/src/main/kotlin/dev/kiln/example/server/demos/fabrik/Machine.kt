package dev.kiln.example.server.demos.fabrik

class Machine(val id: Int, val name: String, val type: MachineType, var recipe: Recipe, var running: Boolean, var priority: Int) {
    var progress = 0.0
    var power = 0.0
    var produced = 0L
    var status = MachineStatus.OFF
}
