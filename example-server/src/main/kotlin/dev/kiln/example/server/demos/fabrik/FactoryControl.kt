package dev.kiln.example.server.demos.fabrik

import com.google.gson.JsonObject
import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.RequestGuard
import dev.kiln.example.server.shop.lua.RequestFields
import java.util.UUID
import kotlin.math.min

/*
 * Fabrik-Steuerzentrale (extension `fabrik`): a simulated factory per player. A power grid with a fixed [supply]
 * is shared by the running machines in order of their priority (high first); each machine needs the power of its
 * recipe and progresses proportionally to the power it gets.
 *
 * C->S  set {req, machine, running?, priority?, recipe?}   at least one of the optional fields
 */

data class Recipe(val id: String, val label: String, val input: String, val output: String, val seconds: Int, val power: Int)

enum class MachineType(val label: String, val icon: String, val recipes: List<Recipe>) {
    SMELTER(
        "Hochofen", "minecraft:blast_furnace",
        listOf(
            Recipe("iron", "Eisenbarren", "minecraft:raw_iron", "minecraft:iron_ingot", 4, 30),
            Recipe("gold", "Goldbarren", "minecraft:raw_gold", "minecraft:gold_ingot", 6, 40),
            Recipe("glass", "Glas", "minecraft:sand", "minecraft:glass", 2, 20),
        ),
    ),
    CRUSHER(
        "Zerkleinerer", "minecraft:grindstone",
        listOf(
            Recipe("gravel", "Kies", "minecraft:cobblestone", "minecraft:gravel", 3, 25),
            Recipe("sand", "Sand", "minecraft:gravel", "minecraft:sand", 3, 25),
        ),
    ),
    ASSEMBLER(
        "Montageband", "minecraft:crafter",
        listOf(
            Recipe("rail", "Schienen", "minecraft:iron_ingot", "minecraft:rail", 5, 35),
            Recipe("piston", "Kolben", "minecraft:iron_ingot", "minecraft:piston", 8, 45),
            Recipe("hopper", "Trichter", "minecraft:iron_ingot", "minecraft:hopper", 10, 50),
        ),
    ),
    SAWMILL(
        "Sägewerk", "minecraft:stonecutter",
        listOf(
            Recipe("planks", "Bretter", "minecraft:oak_log", "minecraft:oak_planks", 1, 10),
            Recipe("sticks", "Stöcke", "minecraft:oak_planks", "minecraft:stick", 1, 8),
        ),
    ),
    ;

    fun recipe(id: String): Recipe? = recipes.find { it.id == id }
}

enum class MachineStatus(val label: String) {
    RUNNING("Läuft"),
    LOW_POWER("Wenig Strom"),
    NO_POWER("Kein Strom"),
    OFF("Aus"),
}

class Machine(val id: Int, val name: String, val type: MachineType, var recipe: Recipe, var running: Boolean, var priority: Int) {
    var progress = 0.0
    var power = 0.0
    var produced = 0L
    var status = MachineStatus.OFF
}

data class RecipeDto(val id: String, val label: String, val input: String, val output: String, val seconds: Int, val power: Int)

data class MachineTypeDto(val id: String, val label: String, val icon: String, val recipes: List<RecipeDto>)

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

data class FactoryState(
    val supply: Int,
    val used: Int,
    val maxPriority: Int,
    val types: List<MachineTypeDto>,
    val machines: List<MachineDto>,
)

class FactoryControl(val supply: Int = 120, private val guard: RequestGuard = RequestGuard()) {
    private val factories = HashMap<UUID, List<Machine>>()

    fun machines(player: UUID): List<Machine> = factories.getOrPut(player) { defaults() }

    private fun defaults(): List<Machine> = listOf(
        Machine(1, "Hochofen Nord", MachineType.SMELTER, MachineType.SMELTER.recipes[0], running = true, priority = 8),
        Machine(2, "Hochofen Süd", MachineType.SMELTER, MachineType.SMELTER.recipes[2], running = true, priority = 5),
        Machine(3, "Brecher", MachineType.CRUSHER, MachineType.CRUSHER.recipes[0], running = true, priority = 4),
        Machine(4, "Montage 1", MachineType.ASSEMBLER, MachineType.ASSEMBLER.recipes[0], running = false, priority = 6),
        Machine(5, "Montage 2", MachineType.ASSEMBLER, MachineType.ASSEMBLER.recipes[1], running = true, priority = 3),
        Machine(6, "Sägewerk", MachineType.SAWMILL, MachineType.SAWMILL.recipes[0], running = true, priority = 2),
    ).onEach { update(it, 0.0) }

    /** Advances [player]'s factory by [seconds]: power by priority, then progress. */
    fun tick(player: UUID, seconds: Double = 1.0) {
        var remaining = supply.toDouble()
        val order = machines(player).sortedWith(compareByDescending<Machine> { it.priority }.thenBy { it.id })
        for (m in order) {
            if (!m.running) {
                update(m, 0.0)
                continue
            }
            val need = m.recipe.power.toDouble()
            val got = min(need, remaining)
            remaining -= got
            update(m, got / need)
            m.progress += m.power * seconds / m.recipe.seconds
            while (m.progress >= 1.0) {
                m.progress -= 1.0
                m.produced++
            }
        }
    }

    private fun update(m: Machine, power: Double) {
        m.power = if (m.running) power else 0.0
        m.status = when {
            !m.running -> MachineStatus.OFF
            power <= 0.0 -> MachineStatus.NO_POWER
            power < 0.999 -> MachineStatus.LOW_POWER
            else -> MachineStatus.RUNNING
        }
    }

    fun state(player: UUID): FactoryState {
        val list = machines(player)
        val used = list.sumOf { it.power * it.recipe.power }.toInt()
        return FactoryState(
            supply, used, MAX_PRIORITY,
            MachineType.entries.map { t -> MachineTypeDto(t.name, t.label, t.icon, t.recipes.map { RecipeDto(it.id, it.label, it.input, it.output, it.seconds, it.power) }) },
            list.map {
                MachineDto(
                    it.id, it.name, it.type.name, it.running, it.priority, it.recipe.id, it.progress, it.power, it.recipe.power,
                    it.produced, it.status.name, it.status.label,
                )
            },
        )
    }

    /** `set`: switches a machine, changes its priority and/or recipe (a new recipe restarts the current item). */
    fun set(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "set", now) { req ->
        val id = RequestFields.int(msg, "machine", 1, 1_000) ?: return@run DemoResult.fail(req, "set", DemoResult.INVALID)
        val machine = machines(player).find { it.id == id } ?: return@run DemoResult.fail(req, "set", "Diese Maschine gibt es nicht.")
        val running = if (msg?.has("running") == true) RequestFields.boolean(msg, "running") ?: return@run DemoResult.fail(req, "set", DemoResult.INVALID) else null
        val priority = if (msg?.has("priority") == true) {
            RequestFields.int(msg, "priority", 1, MAX_PRIORITY)
                ?: return@run DemoResult.fail(req, "set", "Priorität muss zwischen 1 und $MAX_PRIORITY liegen.", mapOf("priority" to "1–$MAX_PRIORITY"))
        } else {
            null
        }
        val recipe = if (msg?.has("recipe") == true) {
            val recipeId = RequestFields.string(msg, "recipe", 32) ?: return@run DemoResult.fail(req, "set", DemoResult.INVALID)
            machine.type.recipe(recipeId)
                ?: return@run DemoResult.fail(req, "set", "${machine.type.label} kann dieses Rezept nicht.", mapOf("recipe" to "Unbekanntes Rezept"))
        } else {
            null
        }
        if (running == null && priority == null && recipe == null) return@run DemoResult.fail(req, "set", DemoResult.INVALID)
        val changes = ArrayList<String>()
        running?.let {
            machine.running = it
            update(machine, if (it) machine.power else 0.0)
            changes += if (it) "eingeschaltet" else "ausgeschaltet"
        }
        priority?.let {
            machine.priority = it
            changes += "Priorität $it"
        }
        recipe?.let {
            if (it != machine.recipe) {
                machine.recipe = it
                machine.progress = 0.0
            }
            changes += "Rezept ${it.label}"
        }
        DemoResult.ok(req, "set", "${machine.name}: ${changes.joinToString(", ")}")
    }

    fun forget(player: UUID) {
        factories.remove(player)
        guard.forget(player)
    }

    companion object {
        const val MAX_PRIORITY = 10
    }
}
