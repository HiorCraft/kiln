package dev.kiln.server.runtime.furniture

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.level.Level

/**
 * A furniture hit box. Same network type and data as a vanilla interaction entity (clients need nothing new to
 * show it), but [solid] ones collide like boats on the server; the Kiln runtime makes them solid on the client too
 * (`EntityTraits`), so movement checks and client physics agree. Never saved (furniture lives in its chunk's data).
 */
class KilnInteraction(level: Level, val solid: Boolean) : Interaction(net.minecraft.world.entity.EntityTypes.INTERACTION, level) {
    override fun canBeCollidedWith(other: Entity?): Boolean = solid
}
