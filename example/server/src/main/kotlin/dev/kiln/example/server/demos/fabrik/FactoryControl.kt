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
        val changes = apply(machine, running, priority, recipe)
        DemoResult.ok(req, "set", "${machine.name}: ${changes.joinToString(", ")}")
    }

    /** Applies the requested changes (`null` = unchanged) to [machine]; returns them as readable text. */
    private fun apply(machine: Machine, running: Boolean?, priority: Int?, recipe: Recipe?): List<String> {
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
        return changes
    }

    fun forget(player: UUID) {
        factories.remove(player)
        guard.forget(player)
    }

    companion object {
        const val MAX_PRIORITY = 10
    }
}
