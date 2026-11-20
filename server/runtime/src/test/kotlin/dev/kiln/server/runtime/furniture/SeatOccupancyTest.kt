package dev.kiln.server.runtime.furniture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SeatOccupancyTest {
    /** Seats count as taken by where riders sit, so seats from the API (`Furniture.seat`) block `sit()` too. */
    @Test
    fun occupiedByRiderPositions() {
        val seats = listOf(Vec(-0.67, 0.5, 0.0), Vec(0.0, 0.5, 0.0), Vec(0.67, 0.5, 0.0))
        assertEquals(setOf(1), FurnitureGeometry.occupiedSeats(seats, listOf(Vec(0.001, 0.5, 0.0))))
        assertEquals(emptySet<Int>(), FurnitureGeometry.occupiedSeats(seats, listOf(Vec(0.3, 0.5, 0.0))))
        assertEquals(setOf(0, 2), FurnitureGeometry.occupiedSeats(seats, listOf(Vec(0.67, 0.5, 0.0), Vec(-0.67, 0.5, 0.0))))
    }
}
