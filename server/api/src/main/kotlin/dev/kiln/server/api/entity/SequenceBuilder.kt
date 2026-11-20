package dev.kiln.server.api.entity

public class SequenceBuilder {
    public val steps: MutableList<SequenceAction> = ArrayList()

    public fun run(action: EntityContext.() -> Unit) {
        steps += SequenceAction.Run(action)
    }

    public fun wait(ticks: Int) {
        require(ticks >= 0) { "wait needs ticks >= 0" }
        steps += SequenceAction.Wait(ticks)
    }

    public fun animate(name: String, playback: AnimationPlayback = AnimationPlayback.ONCE, speed: Float = 1f) {
        run { animate(name, playback, speed) }
    }

    public fun stopMoving() {
        run { stopMoving() }
    }
}
