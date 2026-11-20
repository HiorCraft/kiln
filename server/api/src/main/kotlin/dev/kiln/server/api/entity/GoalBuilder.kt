package dev.kiln.server.api.entity

/**
 * A goal: runs while [canStart] (then [canContinue]) hold and no higher-priority goal with the same controls runs.
 * With a [sequence] it runs until the sequence is done. Errors stop the goal for that entity and are logged.
 */
public class GoalBuilder(public val priority: Int, public val name: String) {
    /** Controls this goal claims: movement and/or looking (two goals with a shared control do not run together). */
    public var movement: Boolean = true
    public var looking: Boolean = true
    public var states: Set<String>? = null
    public var canStart: (EntityContext.() -> Boolean) = { true }
    public var canContinue: (EntityContext.() -> Boolean)? = null
    public var onStart: (EntityContext.() -> Unit)? = null
    public var onTick: (EntityContext.() -> Unit)? = null
    public var onStop: (EntityContext.() -> Unit)? = null
    public var sequence: List<SequenceAction>? = null

    public fun inStates(vararg names: String) {
        states = names.toSet()
    }

    public fun canStart(predicate: EntityContext.() -> Boolean) {
        canStart = predicate
    }

    /** Default: [canStart] again (without a sequence). */
    public fun canContinue(predicate: EntityContext.() -> Boolean) {
        canContinue = predicate
    }

    public fun onStart(handler: EntityContext.() -> Unit) {
        onStart = handler
    }

    public fun tick(handler: EntityContext.() -> Unit) {
        onTick = handler
    }

    public fun onStop(handler: EntityContext.() -> Unit) {
        onStop = handler
    }

    /** A timed sequence ("Ablauf"): the goal runs until it is done. */
    public fun sequence(block: SequenceBuilder.() -> Unit) {
        sequence = SequenceBuilder().apply(block).steps
    }

    /** Preset: walk to random spots within [radius] blocks now and then. */
    public fun wander(radius: Int = 8, speed: Double = 1.0, chancePerTick: Double = 0.01) {
        looking = false
        canStart { random() < chancePerTick && target == null }
        onStart { wanderTo(radius, speed) }
        canContinue { isMoving() }
    }

    /** Preset: look at the nearest player within [range]. */
    public fun lookAtNearestPlayer(range: Double = 8.0) {
        movement = false
        canStart { nearbyPlayers(range).isNotEmpty() }
        tick { nearbyPlayers(range).firstOrNull()?.let(::lookAt) }
    }

    /** Preset: follow the target until [distance] blocks. */
    public fun chaseTarget(speed: Double = 1.0, distance: Double = 2.0) {
        canStart { target?.let { distanceTo(it) > distance } ?: false }
        tick { target?.let { moveTo(it, speed); lookAt(it) } }
        onStop { stopMoving() }
    }
}
