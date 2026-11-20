package dev.kiln.server.api.entity

import org.bukkit.entity.Player

/** Goals (highest priority = lowest number wins), target selection and states of a custom entity. */
public class BehaviorBuilder {
    public val goals: MutableList<GoalBuilder> = ArrayList()
    public val states: MutableMap<String, StateBuilder> = LinkedHashMap()
    public var targeting: EntityTargeting? = null

    /** A goal; [name] appears in logs. */
    public fun goal(priority: Int, name: String, block: GoalBuilder.() -> Unit) {
        goals += GoalBuilder(priority, name).apply(block)
    }

    /** Targets the nearest visible player in survival/adventure within [range] that passes [filter]. */
    public fun targeting(range: Double = 16.0, filter: (Player) -> Boolean = { true }) {
        targeting = EntityTargeting(range, filter)
    }

    /** A behaviour state ([name]: `[A-Za-z0-9_.-]{1,64}`, checked at registration); goals can be limited to states ([GoalBuilder.inStates]). */
    public fun state(name: String, block: StateBuilder.() -> Unit) {
        states[name] = StateBuilder().apply(block)
    }
}
