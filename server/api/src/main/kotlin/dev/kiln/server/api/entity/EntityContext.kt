package dev.kiln.server.api.entity

import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player

/** What behaviour code can do with its entity (main thread). */
public interface EntityContext {
    public val custom: CustomEntity
    public val self: Mob
    public val target: LivingEntity?
    public val state: String
    public val memory: MutableMap<String, Any>

    public fun moveTo(location: Location, speed: Double = 1.0): Boolean
    public fun moveTo(entity: Entity, speed: Double = 1.0): Boolean
    public fun wanderTo(radius: Int, speed: Double = 1.0): Boolean
    public fun stopMoving()
    public fun isMoving(): Boolean
    public fun lookAt(entity: Entity)
    public fun animate(name: String, playback: AnimationPlayback = AnimationPlayback.ONCE, speed: Float = 1f)
    public fun damage(entity: LivingEntity, amount: Double)
    public fun nearbyPlayers(range: Double): List<Player>
    public fun distanceTo(entity: Entity): Double

    /** `true` when cooldown [name] is over (or was never started). */
    public fun cooldownReady(name: String): Boolean
    public fun cooldown(name: String, ticks: Int)

    /** Plays sound event [sound] at the entity for everyone near. */
    public fun playSound(sound: Key, volume: Float = 1f, pitch: Float = 1f)
    public fun switchTo(state: String)
    public fun random(): Double
}
