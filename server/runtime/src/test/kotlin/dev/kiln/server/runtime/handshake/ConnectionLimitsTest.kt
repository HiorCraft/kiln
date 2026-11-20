package dev.kiln.server.runtime.handshake

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionLimitsTest {
    @Test
    fun `concurrent handshakes are limited per address and freed on release`() {
        val slots = HandshakeSlots(maxPerAddress = 2)
        assertTrue(slots.tryAcquire("1.2.3.4"))
        assertTrue(slots.tryAcquire("1.2.3.4"))
        assertFalse(slots.tryAcquire("1.2.3.4"))
        assertTrue(slots.tryAcquire("5.6.7.8"))
        slots.release("1.2.3.4")
        assertTrue(slots.tryAcquire("1.2.3.4"))
    }

    @Test
    fun `zero disables the handshake limit`() {
        val slots = HandshakeSlots(maxPerAddress = 0)
        repeat(100) { assertTrue(slots.tryAcquire("1.2.3.4")) }
    }

    @Test
    fun `download bytes are budgeted per address and window`() {
        var now = 0L
        val budget = DownloadBudget(bytesPerWindow = 1_000, windowMillis = 60_000, clock = { now })
        assertTrue(budget.tryConsume("1.2.3.4", 600))
        assertFalse(budget.tryConsume("1.2.3.4", 600))
        assertTrue(budget.tryConsume("5.6.7.8", 600))
        // nothing to send is always fine
        assertTrue(budget.tryConsume("1.2.3.4", 0))
        now = 60_000
        assertTrue(budget.tryConsume("1.2.3.4", 600))
    }

    @Test
    fun `a single download larger than the budget is allowed once per window`() {
        var now = 0L
        val budget = DownloadBudget(bytesPerWindow = 1_000, windowMillis = 60_000, clock = { now })
        assertTrue(budget.tryConsume("1.2.3.4", 5_000))
        assertFalse(budget.tryConsume("1.2.3.4", 1))
    }

    @Test
    fun `zero disables the download budget`() {
        val budget = DownloadBudget(bytesPerWindow = 0, windowMillis = 60_000)
        repeat(10) { assertTrue(budget.tryConsume("1.2.3.4", Long.MAX_VALUE / 20)) }
    }
}
