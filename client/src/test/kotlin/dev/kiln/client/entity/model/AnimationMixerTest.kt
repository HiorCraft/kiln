package dev.kiln.client.entity.model

import dev.kiln.protocol.message.AnimationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimationMixerTest {
    private val animations = GeoAnimations.parse("""
        {"animations":{
          "animation.g.idle":{"loop":true,"animation_length":2.0,"bones":{"body":{"position":{"0.0":[0,0,0],"1.0":[0,1,0],"2.0":[0,0,0]}}}},
          "animation.g.walk":{"loop":true,"animation_length":1.0,"bones":{"leg":{"rotation":{"0.0":[30,0,0],"0.5":[-30,0,0],"1.0":[30,0,0]}}}},
          "animation.g.smash":{"animation_length":1.0,"bones":{"arm":{"rotation":{"0.0":[0,0,0],"1.0":[-90,0,0]}},"leg":{"rotation":[5,0,0]}}}
        }}
    """.trimIndent())

    @Test
    fun stateAnimationLoops() {
        val result = AnimationMixer.mix(animations, "idle", stateTime = 3.0, active = emptyList(), now = 0.0)
        assertEquals(1.0, result.poses.getValue("body").position.y, 1e-6) // 3.0 mod 2.0 = 1.0
    }

    @Test
    fun serverAnimationOverridesItsBonesOnly() {
        val smash = ActiveAnimation("smash", AnimationMode.ONCE, 1f, start = 10.0)
        val result = AnimationMixer.mix(animations, "walk", stateTime = 0.0, active = listOf(smash), now = 10.5)
        assertEquals(-45.0, result.poses.getValue("arm").rotation.x, 1e-6)
        assertEquals(5.0, result.poses.getValue("leg").rotation.x, 1e-6) // smash defines the leg: it wins over walk
        assertTrue(result.finished.isEmpty())
    }

    @Test
    fun onceEndsHoldStaysLoopRepeats() {
        val once = ActiveAnimation("smash", AnimationMode.ONCE, 1f, start = 0.0)
        val hold = ActiveAnimation("smash", AnimationMode.HOLD, 1f, start = 0.0)
        val loop = ActiveAnimation("smash", AnimationMode.LOOP, 2f, start = 0.0)
        assertEquals(listOf(once), AnimationMixer.mix(animations, null, 0.0, listOf(once), now = 1.5).finished)
        assertEquals(-90.0, AnimationMixer.mix(animations, null, 0.0, listOf(hold), now = 5.0).poses.getValue("arm").rotation.x, 1e-6)
        // speed 2: 1.25 s real time = 2.5 s animation time = 0.5 into the second loop
        assertEquals(-45.0, AnimationMixer.mix(animations, null, 0.0, listOf(loop), now = 1.25).poses.getValue("arm").rotation.x, 1e-6)
    }

    @Test
    fun unknownAnimationsAreIgnored() {
        val result = AnimationMixer.mix(animations, "fly", 0.0, listOf(ActiveAnimation("nope", AnimationMode.LOOP, 1f, 0.0)), now = 1.0)
        assertTrue(result.poses.isEmpty())
    }
}
