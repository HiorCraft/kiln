package dev.kiln.client.entity

import dev.kiln.client.entity.model.ActiveAnimation
import dev.kiln.protocol.message.EntityAppearance
import net.minecraft.client.Minecraft

/**
 * Custom entity looks of the current connection (`EntityAppearance`, `PlayAnimation`, `StopAnimation`) by entity id.
 * Kept by id because messages may arrive before the entity exists on the client. Render thread only.
 */
object KilnEntityAppearances {
    private val entries = HashMap<Int, AppearanceEntry>()

    fun get(entityId: Int): AppearanceEntry? = entries[entityId]

    fun isEmpty(): Boolean = entries.isEmpty()

    fun set(appearance: EntityAppearance) {
        val old = entries[appearance.entityId]
        val entry = AppearanceEntry(appearance)
        if (old != null && old.appearance.model == appearance.model) entry.animations += old.animations
        entries[appearance.entityId] = entry
        refreshDimensions(appearance.entityId)
    }

    fun play(entityId: Int, name: String, mode: Int, speed: Float, now: Double) {
        val entry = entries[entityId] ?: return
        entry.animations.removeIf { it.name == name }
        if (entry.animations.size >= MAX_ACTIVE) entry.animations.removeAt(0)
        entry.animations += ActiveAnimation(name, mode, speed, now)
    }

    fun stop(entityId: Int, name: String?) {
        val entry = entries[entityId] ?: return
        if (name == null) entry.animations.clear() else entry.animations.removeIf { it.name == name }
    }

    fun remove(entityIds: List<Int>) {
        for (id in entityIds) if (entries.remove(id) != null) refreshDimensions(id)
    }

    fun clear() = entries.clear()

    /** The entity's bounding box comes from the appearance (`LivingEntityDimensionsMixin`): recompute it. */
    fun refreshDimensions(entityId: Int) {
        Minecraft.getInstance().level?.getEntity(entityId)?.refreshDimensions()
    }

    private const val MAX_ACTIVE = 8
}
