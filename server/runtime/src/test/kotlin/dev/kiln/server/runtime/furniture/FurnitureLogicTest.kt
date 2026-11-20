package dev.kiln.server.runtime.furniture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FurnitureLogicTest {
    @Test
    fun yawSnapsTo90WithHitboxes() {
        assertEquals(90f, FurnitureGeometry.snapYaw(80f, hasHitboxes = true))
        assertEquals(0f, FurnitureGeometry.snapYaw(-30f, hasHitboxes = true))
        assertEquals(180f, FurnitureGeometry.snapYaw(-170f, hasHitboxes = true))
        assertEquals(330f, FurnitureGeometry.snapYaw(-30f, hasHitboxes = false))
    }

    @Test
    fun offsetsRotateWithYaw() {
        val forward = Vec(0.0, 0.5, 1.0)
        assertEquals(Vec(0.0, 0.5, 1.0), FurnitureGeometry.rotate(forward, 0f))
        assertEquals(Vec(-1.0, 0.5, 0.0), FurnitureGeometry.rotate(forward, 90f))
        assertEquals(Vec(0.0, 0.5, -1.0), FurnitureGeometry.rotate(forward, 180f))
        assertEquals(Vec(1.0, 0.5, 0.0), FurnitureGeometry.rotate(forward, 270f))
    }

    @Test
    fun boxesAreCenteredOnRotatedOffsets() {
        val boxes = FurnitureGeometry.boxes(Vec(10.5, 64.0, 20.5), 90f, listOf(Hitbox(1.0, 0.5, Vec(0.0, 0.0, 0.0), true), Hitbox(1.0, 0.6, Vec(0.0, 0.5, 0.4), true)))
        assertEquals(Box(10.0, 64.0, 20.0, 11.0, 64.5, 21.0), boxes[0])
        assertEquals(Box(9.6, 64.5, 20.0, 10.6, 65.1, 21.0), boxes[1].rounded())
    }

    @Test
    fun placementBlockedByBlocks() {
        val boxes = listOf(Box(10.0, 64.0, 20.0, 11.0, 64.5, 21.0))
        assertFalse(FurnitureGeometry.blocked(boxes) { x, y, z -> y == 63 }) // floor below is fine
        assertTrue(FurnitureGeometry.blocked(boxes) { x, y, z -> x == 10 && y == 64 && z == 20 })
        assertFalse(FurnitureGeometry.blocked(boxes) { x, y, z -> x == 11 && y == 64 && z == 20 }) // touching only
    }

    @Test
    fun storeRoundTrip() {
        val list = listOf(
            StoredFurniture("kiln_content:chair", 10.5, 64.0, -3.25, 90f, UUID(1, 2)),
            StoredFurniture("kiln_content:table", 0.0, -60.0, 1e6, 270f, UUID(3, 4)),
        )
        assertEquals(list, FurnitureStore.decode(FurnitureStore.encode(list)).entries)
    }

    @Test
    fun garbageIsIgnored() {
        val text = FurnitureStore.encode(listOf(StoredFurniture("a:b", 1.0, 2.0, 3.0, 0f, UUID(5, 6)))) + "\nnonsense;;\n;;;;;\na:b;x;2;3;0;not-a-uuid"
        val decoded = FurnitureStore.decode(text)
        assertEquals(1, decoded.entries.size)
        assertEquals(3, decoded.invalid)
    }

    @Test
    fun nearestFreeSeatIsChosen() {
        val seats = listOf(Vec(-0.67, 0.5, 0.0), Vec(0.0, 0.5, 0.0), Vec(0.67, 0.5, 0.0))
        assertEquals(2, FurnitureGeometry.nearestFreeSeat(seats, emptySet(), Vec(0.9, 0.4, 0.3)))
        assertEquals(1, FurnitureGeometry.nearestFreeSeat(seats, setOf(2), Vec(0.9, 0.4, 0.3)))
        assertEquals(null, FurnitureGeometry.nearestFreeSeat(seats, setOf(0, 1, 2), Vec(0.0, 0.0, 0.0)))
        assertEquals(null, FurnitureGeometry.nearestFreeSeat(emptyList(), emptySet(), Vec(0.0, 0.0, 0.0)))
    }
}

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
