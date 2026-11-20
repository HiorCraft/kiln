package dev.kiln.server.runtime.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomEntityStoreTest {
    @Test
    fun roundTripKeepsHealthAndState() {
        val list = listOf(
            StoredEntity("kiln_content:golem", 1.5, 64.0, -3.25, 90f, 37.5, "enraged", UUID(1, 2)),
            StoredEntity("kiln_content:golem", 0.0, 70.0, 0.0, 0f, 80.0, "default", UUID(3, 4)),
        )
        assertEquals(list, CustomEntityStore.decode(CustomEntityStore.encode(list)).entries)
    }

    @Test
    fun garbageIsCountedAndSkipped() {
        val text = CustomEntityStore.encode(listOf(StoredEntity("a:b", 1.0, 2.0, 3.0, 0f, 5.0, "default", UUID(5, 6)))) + "\nbroken\na:b;1;2;3;0;x;default;" + UUID(7, 8)
        val decoded = CustomEntityStore.decode(text)
        assertEquals(1, decoded.entries.size)
        assertEquals(2, decoded.invalid)
    }
}
