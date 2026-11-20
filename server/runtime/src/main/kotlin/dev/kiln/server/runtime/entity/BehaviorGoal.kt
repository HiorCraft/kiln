package dev.kiln.server.runtime.entity

import dev.kiln.server.api.entity.EntityContext
import dev.kiln.server.api.entity.GoalBuilder
import dev.kiln.server.api.entity.SequenceAction
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/** A [GoalBuilder] on the NMS goal selector. An exception stops the goal for this entity for good. */
internal class BehaviorGoal(private val entity: LiveEntity, private val def: GoalBuilder) : Goal() {
    private var broken = false
    private var runner: SequenceRunner<EntityContext>? = null

    init {
        val flags = EnumSet.noneOf(Flag::class.java)
        if (def.movement) flags += Flag.MOVE
        if (def.looking) flags += Flag.LOOK
        setFlags(flags)
    }

    private inline fun <T> guarded(fallback: T, what: String, block: () -> T): T {
        if (broken) return fallback
        return try {
            block()
        } catch (e: Throwable) {
            broken = true
            entity.hook("goal ${def.name} ($what)") { throw e }
            fallback
        }
    }

    private fun inState(): Boolean = def.states?.contains(entity.stateName) ?: true

    override fun canUse(): Boolean = inState() && guarded(false, "canStart") { def.canStart(entity.context) }

    override fun canContinueToUse(): Boolean {
        if (!inState() || broken) return false
        runner?.let { return !it.finished }
        return guarded(false, "canContinue") { (def.canContinue ?: def.canStart)(entity.context) }
    }

    override fun start() {
        runner = def.sequence?.let { actions -> SequenceRunner(actions.map(::toStep)) }
        guarded(Unit, "onStart") { def.onStart?.invoke(entity.context) }
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        guarded(Unit, "tick") {
            def.onTick?.invoke(entity.context)
            runner?.tick(entity.context)
        }
    }

    override fun stop() {
        runner = null
        guarded(Unit, "onStop") { def.onStop?.invoke(entity.context) }
    }

    private fun toStep(action: SequenceAction): SequenceStep<EntityContext> = when (action) {
        is SequenceAction.Run -> SequenceStep.Run { context -> action.action(context) }
        is SequenceAction.Wait -> SequenceStep.Wait(action.ticks)
    }
}
