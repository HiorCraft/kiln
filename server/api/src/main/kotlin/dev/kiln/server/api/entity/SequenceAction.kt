package dev.kiln.server.api.entity

/** A step of a [GoalBuilder.sequence]. */
public sealed interface SequenceAction {
    public class Run(public val action: EntityContext.() -> Unit) : SequenceAction
    public class Wait(public val ticks: Int) : SequenceAction
}
