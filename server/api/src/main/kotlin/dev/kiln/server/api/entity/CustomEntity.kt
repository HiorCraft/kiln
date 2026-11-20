package dev.kiln.server.api.entity

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob

/** A living custom entity. */
public interface CustomEntity {
    public val type: CustomEntityType

    /** The Bukkit mob (position, health, …); valid while the entity lives. */
    public val entity: Mob

    /**
     * Current behaviour state (see [BehaviorBuilder.state]); `"default"` initially. Saved with the entity; after a
     * restore the state is set again without calling its `onEnter`.
     *
     * @throws IllegalArgumentException name not matching `[A-Za-z0-9_.-]{1,64}`.
     */
    public var state: String

    public val target: LivingEntity?

    /** Free storage for the behaviour (not persisted). */
    public val memory: MutableMap<String, Any>

    /**
     * Plays [name] from the model's animation file for every player that sees the entity ([speed] clamped to 0..8).
     *
     * @throws IllegalArgumentException name not matching `[a-z0-9_.]{1,64}`.
     */
    public fun playAnimation(name: String, playback: AnimationPlayback = AnimationPlayback.ONCE, speed: Float = 1f)

    /** Stops [name], or every server animation (`null`). */
    public fun stopAnimation(name: String? = null)
}
