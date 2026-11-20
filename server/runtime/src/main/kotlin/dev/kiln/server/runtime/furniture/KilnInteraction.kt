package dev.kiln.server.runtime.furniture

import net.minecraft.server.level.ServerPlayer
import org.bukkit.craftbukkit.event.CraftEventFactory
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.level.Level

/**
 * A furniture hit box. Same network type and data as a vanilla interaction entity (clients need nothing new to
 * show it), but [solid] ones collide like boats on the server; the Kiln runtime makes them solid on the client too
 * (`EntityTraits`), so movement checks and client physics agree. Never saved (furniture lives in its chunk's data).
 *
 * Attacks on interaction entities never reach `PrePlayerAttackEntityEvent` (vanilla handles them in
 * [skipAttackInteraction]), so a player's left click is reported to [onAttacked] here.
 */
class KilnInteraction(
    level: Level,
    val solid: Boolean,
    private val onAttacked: (org.bukkit.entity.Player) -> Unit,
) : Interaction(net.minecraft.world.entity.EntityTypes.INTERACTION, level) {
    override fun canBeCollidedWith(other: Entity?): Boolean = solid

    override fun skipAttackInteraction(attacker: Entity): Boolean {
        if (attacker is ServerPlayer) {
            // same event vanilla Paper fires for interaction entities; protection plugins cancel it
            val event = CraftEventFactory.callNonLivingEntityDamageEvent(this, attacker.damageSources().generic().eventEntityDamager(attacker), 1.0, false)
            if (event == null || !event.isCancelled) onAttacked(attacker.bukkitEntity)
            return true
        }
        return super.skipAttackInteraction(attacker)
    }
}
