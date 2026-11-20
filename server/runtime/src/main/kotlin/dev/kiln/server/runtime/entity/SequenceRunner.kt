package dev.kiln.server.runtime.entity

/** One step of a timed behaviour sequence ("Ablauf") on context [C]. */
sealed interface SequenceStep<in C> {
    /** Runs right away; consecutive runs happen in the same tick. */
    class Run<C>(val action: (C) -> Unit) : SequenceStep<C>

    /** Pauses for [ticks] ticks. */
    class Wait(val ticks: Int) : SequenceStep<Any?>
}

/**
 * Runs [steps] tick by tick without threads: [tick] executes every run step up to the next wait and returns `true`
 * once the sequence is finished. Exceptions of a step reach the caller (it stops the goal).
 */
class SequenceRunner<C>(private val steps: List<SequenceStep<C>>) {
    private var index = 0
    private var waiting = 0

    val finished: Boolean get() = index >= steps.size && waiting == 0

    fun tick(context: C): Boolean {
        if (waiting > 0 && --waiting > 0) return false
        while (index < steps.size) {
            when (val step = steps[index++]) {
                is SequenceStep.Run -> step.action(context)
                is SequenceStep.Wait -> if (step.ticks > 0) {
                    waiting = step.ticks
                    return false
                }
            }
        }
        return true
    }
}

/** Named cooldowns in ticks of a game clock. */
class Cooldowns {
    private val until = HashMap<String, Long>()

    fun ready(name: String, now: Long): Boolean = now >= (until[name] ?: Long.MIN_VALUE)

    fun start(name: String, ticks: Int, now: Long) {
        until[name] = now + ticks
    }
}
