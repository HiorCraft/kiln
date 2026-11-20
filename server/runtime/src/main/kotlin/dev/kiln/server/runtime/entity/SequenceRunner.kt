package dev.kiln.server.runtime.entity

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
