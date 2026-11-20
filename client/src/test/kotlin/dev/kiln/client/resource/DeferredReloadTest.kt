package dev.kiln.client.resource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The pack reload after a disconnect must wait until the disconnect is done (it drops all queued tasks). */
class DeferredReloadTest {
    @Test
    fun reloadWaitsUntilItMayRun() {
        var ready = false
        var reloads = 0
        val deferred = DeferredReload({ ready }) { reloads++ }
        deferred.request()
        deferred.tick()
        assertEquals(0, reloads)
        ready = true
        deferred.tick()
        deferred.tick()
        assertEquals(1, reloads)
    }

    @Test
    fun nothingRequestedNothingReloaded() {
        var reloads = 0
        val deferred = DeferredReload({ true }) { reloads++ }
        deferred.tick()
        assertEquals(0, reloads)
    }
}
