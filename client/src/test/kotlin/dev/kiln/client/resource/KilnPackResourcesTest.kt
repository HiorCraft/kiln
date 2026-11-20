package dev.kiln.client.resource

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KilnPackResourcesTest {
    private val pack = KilnPackResources(
        mapOf(
            "demo/models/item/ruby.json" to "{}".toByteArray(),
            "demo/textures/item/ruby.png" to byteArrayOf(1),
            "demo/textures/block/x.png" to byteArrayOf(2),
            "other/textures/item/y.png" to byteArrayOf(3),
        ),
    )

    @Test
    fun listsOnlyRequestedNamespaceAndPrefix() {
        val found = ArrayList<Identifier>()
        pack.listResources(PackType.CLIENT_RESOURCES, "demo", "textures/item") { id, _ -> found += id }
        assertEquals(listOf(Identifier.fromNamespaceAndPath("demo", "textures/item/ruby.png")), found)
    }

    @Test
    fun namespacesFromFiles() {
        assertEquals(setOf("demo", "other"), pack.getNamespaces(PackType.CLIENT_RESOURCES))
    }

    @Test
    fun serverDataIsEmpty() {
        assertTrue(pack.getNamespaces(PackType.SERVER_DATA).isEmpty())
        assertNull(pack.getResource(PackType.SERVER_DATA, Identifier.fromNamespaceAndPath("demo", "models/item/ruby.json")))
    }

    @Test
    fun resourcesAreReadable() {
        val supplier = pack.getResource(PackType.CLIENT_RESOURCES, Identifier.fromNamespaceAndPath("demo", "models/item/ruby.json"))
        assertNotNull(supplier)
        assertEquals("{}", supplier!!.get().readAllBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun sourceAnnouncesPackOnlyWithFiles() {
        KilnPackSource.clear()
        val packs = ArrayList<String>()
        KilnPackSource.loadPacks { packs += it.id }
        assertTrue(packs.isEmpty())
        KilnPackSource.set(mapOf("demo/a.json" to "{}".toByteArray()))
        KilnPackSource.loadPacks { packs += it.id }
        assertEquals(listOf(KilnPackSource.PACK_ID), packs)
        assertTrue(KilnPackSource.clear())
        assertTrue(!KilnPackSource.clear())
    }
}
