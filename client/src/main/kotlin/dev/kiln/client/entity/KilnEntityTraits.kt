package dev.kiln.client.entity

import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/**
 * Traits the server attached to entity ids (`EntityTraits`): currently which interaction entities are solid
 * furniture hit boxes. Kept by id because the message may arrive before the entity is spawned on the client.
 * Render/network thread only.
 */
class KilnEntityTraits {
    private val solid = IntOpenHashSet()

    fun set(entityId: Int, solid: Boolean) {
        if (solid) this.solid.add(entityId) else this.solid.remove(entityId)
    }

    fun remove(entityIds: List<Int>) {
        for (id in entityIds) solid.remove(id)
    }

    fun clear() = solid.clear()

    fun isSolid(entityId: Int): Boolean = solid.contains(entityId)

    companion object {
        @JvmField
        val CLIENT: KilnEntityTraits = KilnEntityTraits()
    }
}
