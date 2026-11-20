package dev.kiln.server.runtime.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SequenceRunnerTest {
    @Test
    fun stepsRunInOrderWithWaits() {
        val log = ArrayList<String>()
        val runner = SequenceRunner<Unit>(listOf(SequenceStep.Run { log += "a" }, SequenceStep.Wait(2), SequenceStep.Run { log += "b" }))
        assertFalse(runner.tick(Unit))
        assertEquals(listOf("a"), log)
        assertFalse(runner.tick(Unit))
        assertTrue(runner.tick(Unit))
        assertEquals(listOf("a", "b"), log)
        assertTrue(runner.finished)
    }

    @Test
    fun consecutiveRunsHappenInOneTick() {
        var n = 0
        val runner = SequenceRunner<Unit>(listOf(SequenceStep.Run { n++ }, SequenceStep.Run { n++ }))
        assertTrue(runner.tick(Unit))
        assertEquals(2, n)
    }

    @Test
    fun errorsPropagateToTheCaller() {
        val runner = SequenceRunner<Unit>(listOf(SequenceStep.Run { error("boom") }))
        val e = runCatching { runner.tick(Unit) }.exceptionOrNull()
        assertEquals("boom", e?.message)
    }

    @Test
    fun cooldownsCountTicks() {
        val cooldowns = Cooldowns()
        assertTrue(cooldowns.ready("smash", now = 100))
        cooldowns.start("smash", ticks = 60, now = 100)
        assertFalse(cooldowns.ready("smash", now = 159))
        assertTrue(cooldowns.ready("smash", now = 160))
    }
}
