package dev.kiln.server.runtime.entity

/** One step of a timed behaviour sequence ("Ablauf") on context [C]. */
sealed interface SequenceStep<in C> {
    /** Runs right away; consecutive runs happen in the same tick. */
    class Run<C>(val action: (C) -> Unit) : SequenceStep<C>

    /** Pauses for [ticks] ticks. */
    class Wait(val ticks: Int) : SequenceStep<Any?>
}
