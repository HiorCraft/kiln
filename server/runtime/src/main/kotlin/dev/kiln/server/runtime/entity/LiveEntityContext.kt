package dev.kiln.server.runtime.entity

import dev.kiln.server.api.entity.AnimationPlayback
import dev.kiln.server.api.entity.CustomEntity
import dev.kiln.server.api.entity.EntityContext
import net.kyori.adventure.key.Key
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import kotlin.random.Random

/** The [EntityContext] behaviour code of a [LiveEntity] works with. */
internal class LiveEntityContext(private val live: LiveEntity) : EntityContext {
    private val bukkit: Mob get() = live.bukkit

    override val custom: CustomEntity get() = live
    override val self: Mob get() = bukkit
    override val target: LivingEntity? get() = bukkit.target
    override val state: String get() = live.stateName
    override val memory: MutableMap<String, Any> get() = live.memory

    override fun moveTo(location: Location, speed: Double): Boolean = bukkit.pathfinder.moveTo(location, speed)
    override fun moveTo(entity: Entity, speed: Double): Boolean =
        if (entity is LivingEntity) bukkit.pathfinder.moveTo(entity, speed) else bukkit.pathfinder.moveTo(entity.location, speed)

    override fun wanderTo(radius: Int, speed: Double): Boolean {
        val pos = DefaultRandomPos.getPos(live.mob, radius, WANDER_VERTICAL_RANGE) ?: return false
        return bukkit.pathfinder.moveTo(Location(bukkit.world, pos.x, pos.y, pos.z), speed)
    }

    override fun stopMoving() = bukkit.pathfinder.stopPathfinding()
    override fun isMoving(): Boolean = bukkit.pathfinder.hasPath()
    override fun lookAt(entity: Entity) = bukkit.lookAt(entity)
    override fun animate(name: String, playback: AnimationPlayback, speed: Float) = live.playAnimation(name, playback, speed)
    override fun damage(entity: LivingEntity, amount: Double) = entity.damage(amount, bukkit)

    override fun nearbyPlayers(range: Double): List<Player> =
        bukkit.world.getNearbyPlayers(bukkit.location, range).filter { it.gameMode in TARGETABLE_MODES }
            .sortedBy { it.location.distanceSquared(bukkit.location) }

    override fun distanceTo(entity: Entity): Double = if (entity.world == bukkit.world) entity.location.distance(bukkit.location) else Double.MAX_VALUE
    override fun cooldownReady(name: String): Boolean = live.cooldowns.ready(name, bukkit.world.gameTime)
    override fun cooldown(name: String, ticks: Int) = live.cooldowns.start(name, ticks, bukkit.world.gameTime)
    override fun switchTo(state: String) {
        live.state = state
    }

    override fun random(): Double = Random.nextDouble()
    override fun playSound(sound: Key, volume: Float, pitch: Float) = live.playSound(sound, volume, pitch)

    private companion object {
        /** Vertical search range of [DefaultRandomPos] for [wanderTo]. */
        const val WANDER_VERTICAL_RANGE = 7
        val TARGETABLE_MODES = setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)
    }
}
