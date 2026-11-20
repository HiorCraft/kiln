package dev.kiln.server.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterTest {
    @Test
    fun `drops excess within a window and recovers in the next`() {
        var now = 10_000L
        val limiter = RateLimiter(3, 1_000) { now }
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(2, limiter.rejectedInWindow)
        now += 999
        assertFalse(limiter.tryAcquire())
        now += 1
        assertTrue(limiter.tryAcquire())
        assertEquals(0, limiter.rejectedInWindow)
    }
}
