package dev.kiln.server.runtime.furniture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandUpTest {
    /** After a dismount the rider stands up on the furniture only if the dismount was getting up, not a teleport/death. */
    @Test
    fun standUpOnlyNearTheSeat() {
        assertTrue(FurnitureGeometry.shouldStandUp(alive = true, sameWorld = true, distanceSquared = 0.5))
        assertFalse(FurnitureGeometry.shouldStandUp(alive = true, sameWorld = true, distanceSquared = 100.0))
        assertFalse(FurnitureGeometry.shouldStandUp(alive = true, sameWorld = false, distanceSquared = 0.0))
        assertFalse(FurnitureGeometry.shouldStandUp(alive = false, sameWorld = true, distanceSquared = 0.0))
    }
}
