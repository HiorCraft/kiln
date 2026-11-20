package dev.kiln.server.runtime.entity

import net.minecraft.world.entity.ai.goal.Goal
import org.bukkit.entity.Player
import java.util.EnumSet

/**
 * Picks the nearest visible survival/adventure player in range as target (checked every [RETARGET_TICKS] ticks).
 * Never runs as a goal itself: it only sets the mob's target.
 */
internal class TargetGoal(private val entity: LiveEntity, private val range: Double, private val filter: (Player) -> Boolean) : Goal() {
    private var cooldown = 0

    init {
        setFlags(EnumSet.of(Flag.TARGET))
    }

    override fun canUse(): Boolean {
        if (--cooldown > 0) return false
        cooldown = RETARGET_TICKS
        if (!hasTargetInRange()) entity.bukkit.target = nearestAcceptedPlayer()
        return false
    }

    private fun hasTargetInRange(): Boolean {
        val mob = entity.bukkit
        val current = mob.target ?: return false
        return current.isValid && current.location.world == mob.world && current.location.distance(mob.location) <= range
    }

    private fun nearestAcceptedPlayer(): Player? =
        entity.context.nearbyPlayers(range).firstOrNull { entity.bukkit.hasLineOfSight(it) && runCatching { filter(it) }.getOrDefault(false) }

    private companion object {
        const val RETARGET_TICKS = 10
    }
}
