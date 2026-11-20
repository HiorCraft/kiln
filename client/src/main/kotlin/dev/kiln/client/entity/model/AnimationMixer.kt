package dev.kiln.client.entity.model

import dev.kiln.protocol.message.AnimationMode

/**
 * Combines the state animation (`idle`/`walk`, looping, chosen by the client from the movement) with the server's
 * animations: every bone a server animation animates takes that animation's values (the latest one wins), all
 * other bones keep the state animation. ONCE animations past their length are reported in [MixResult.finished].
 */
object AnimationMixer {
    fun mix(animations: GeoAnimations, state: String?, stateTime: Double, active: List<ActiveAnimation>, now: Double): MixResult {
        val poses = HashMap<String, BonePose>()
        state?.let { animations.animations[it] }?.let { apply(it, loopTime(it, stateTime), poses) }
        val finished = ArrayList<ActiveAnimation>()
        for (anim in active) {
            val animation = animations.animations[anim.name] ?: continue
            val elapsed = (now - anim.start) * anim.speed
            val time = when (anim.mode) {
                AnimationMode.LOOP -> loopTime(animation, elapsed)
                AnimationMode.HOLD -> elapsed.coerceAtMost(animation.length)
                else -> {
                    if (elapsed > animation.length) {
                        finished += anim
                        continue
                    }
                    elapsed
                }
            }
            apply(animation, time, poses)
        }
        return MixResult(poses, finished)
    }

    private fun loopTime(animation: GeoAnimation, time: Double): Double =
        if (animation.length <= 0.0) 0.0 else ((time % animation.length) + animation.length) % animation.length

    private fun apply(animation: GeoAnimation, time: Double, poses: MutableMap<String, BonePose>) {
        for ((bone, channels) in animation.bones) {
            poses[bone] = BonePose(
                rotation = channels.rotation?.sample(time) ?: Vec3d.ZERO,
                position = channels.position?.sample(time) ?: Vec3d.ZERO,
                scale = channels.scale?.sample(time) ?: Vec3d.ONE,
            )
        }
    }
}
