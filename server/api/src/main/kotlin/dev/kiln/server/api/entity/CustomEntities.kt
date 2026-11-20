package dev.kiln.server.api.entity

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Custom entities: a mob with its own bone model (Bedrock geometry + animations from an extension's resources,
 * drawn by the Kiln runtime), its own hit box and a behaviour written with [BehaviorBuilder] (goals, states,
 * timed sequences). On the server it is a mob without any vanilla behaviour; it is stored with its chunk and
 * comes back when the chunk loads (while its type is registered).
 */
public interface CustomEntities {
    /**
     * Registers entity type [key] (namespace = an extension of [plugin] that has
     * `assets/<ns>/kiln/entity/<path>.geo.json`). Removed when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace or missing model; @throws IllegalStateException duplicate key.
     */
    public fun register(plugin: Plugin, key: Key, init: CustomEntityBuilder.() -> Unit): CustomEntityType

    public fun get(key: Key): CustomEntityType?

    /** The custom entity [entity] is, if any. */
    public fun of(entity: Entity?): CustomEntity?
}

public interface CustomEntityType : Registration {
    public val key: Key
    public val plugin: Plugin

    public fun spawn(location: Location): CustomEntity
}

public enum class AnimationPlayback { ONCE, LOOP, HOLD }

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

/** Definition of a custom entity type. */
public class CustomEntityBuilder {
    /** Bone model `namespace:name`; `null` = the key. */
    public var model: Key? = null
    public var displayName: Component? = null
    public var width: Double = 0.6
    public var height: Double = 1.8
    public var health: Double = 20.0

    /** Movement speed attribute (vanilla zombie: 0.23). */
    public var speed: Double = 0.25
    public var attackDamage: Double = 2.0

    /** The `head` bone follows where the entity looks. */
    public var headBone: Boolean = true

    public val behavior: BehaviorBuilder = BehaviorBuilder()
    public val sounds: EntitySounds = EntitySounds()

    /** Sounds of the entity (sound event keys, also from extension resources). */
    public fun sounds(block: EntitySounds.() -> Unit) {
        sounds.block()
    }
    public var onSpawn: (EntityContext.() -> Unit)? = null
    public var onDamage: (EntityContext.(source: Entity?, amount: Double) -> Unit)? = null
    public var onDeath: (EntityContext.() -> Unit)? = null

    public fun behavior(block: BehaviorBuilder.() -> Unit) {
        behavior.block()
    }

    public fun onSpawn(handler: EntityContext.() -> Unit) {
        onSpawn = handler
    }

    /** After damage was dealt ([amount] after armour); `source` = the attacker if any. */
    public fun onDamage(handler: EntityContext.(source: Entity?, amount: Double) -> Unit) {
        onDamage = handler
    }

    public fun onDeath(handler: EntityContext.() -> Unit) {
        onDeath = handler
    }
}

/** Sound events a custom entity plays by itself; `null` = silent. */
public class EntitySounds {
    /** Now and then while alive (about every 8 seconds on average). */
    public var ambient: Key? = null
    public var hurt: Key? = null
    public var death: Key? = null

    /** While walking, every [stepInterval] ticks. */
    public var step: Key? = null
    public var stepInterval: Int = 10
    public var volume: Float = 1f
    public var pitch: Float = 1f
}

/** Goals (highest priority = lowest number wins), target selection and states of a custom entity. */
public class BehaviorBuilder {
    public class Targeting(public val range: Double, public val filter: (Player) -> Boolean)

    public val goals: MutableList<GoalBuilder> = ArrayList()
    public val states: MutableMap<String, StateBuilder> = LinkedHashMap()
    public var targeting: Targeting? = null

    /** A goal; [name] appears in logs. */
    public fun goal(priority: Int, name: String, block: GoalBuilder.() -> Unit) {
        goals += GoalBuilder(priority, name).apply(block)
    }

    /** Targets the nearest visible player in survival/adventure within [range] that passes [filter]. */
    public fun targeting(range: Double = 16.0, filter: (Player) -> Boolean = { true }) {
        targeting = Targeting(range, filter)
    }

    /** A behaviour state ([name]: `[A-Za-z0-9_.-]{1,64}`, checked at registration); goals can be limited to states ([GoalBuilder.inStates]). */
    public fun state(name: String, block: StateBuilder.() -> Unit) {
        states[name] = StateBuilder().apply(block)
    }
}

public class StateBuilder {
    public var onEnter: (EntityContext.() -> Unit)? = null
    public var onExit: (EntityContext.() -> Unit)? = null

    public fun onEnter(handler: EntityContext.() -> Unit) {
        onEnter = handler
    }

    public fun onExit(handler: EntityContext.() -> Unit) {
        onExit = handler
    }
}

/**
 * A goal: runs while [canStart] (then [canContinue]) hold and no higher-priority goal with the same controls runs.
 * With a [sequence] it runs until the sequence is done. Errors stop the goal for that entity and are logged.
 */
public class GoalBuilder(public val priority: Int, public val name: String) {
    /** Controls this goal claims: movement and/or looking (two goals with a shared control do not run together). */
    public var movement: Boolean = true
    public var looking: Boolean = true
    public var states: Set<String>? = null
    public var canStart: (EntityContext.() -> Boolean) = { true }
    public var canContinue: (EntityContext.() -> Boolean)? = null
    public var onStart: (EntityContext.() -> Unit)? = null
    public var onTick: (EntityContext.() -> Unit)? = null
    public var onStop: (EntityContext.() -> Unit)? = null
    public var sequence: List<SequenceAction>? = null

    public fun inStates(vararg names: String) {
        states = names.toSet()
    }

    public fun canStart(predicate: EntityContext.() -> Boolean) {
        canStart = predicate
    }

    /** Default: [canStart] again (without a sequence). */
    public fun canContinue(predicate: EntityContext.() -> Boolean) {
        canContinue = predicate
    }

    public fun onStart(handler: EntityContext.() -> Unit) {
        onStart = handler
    }

    public fun tick(handler: EntityContext.() -> Unit) {
        onTick = handler
    }

    public fun onStop(handler: EntityContext.() -> Unit) {
        onStop = handler
    }

    /** A timed sequence ("Ablauf"): the goal runs until it is done. */
    public fun sequence(block: SequenceBuilder.() -> Unit) {
        sequence = SequenceBuilder().apply(block).steps
    }

    /** Preset: walk to random spots within [radius] blocks now and then. */
    public fun wander(radius: Int = 8, speed: Double = 1.0, chancePerTick: Double = 0.01) {
        looking = false
        canStart { random() < chancePerTick && target == null }
        onStart { wanderTo(radius, speed) }
        canContinue { isMoving() }
    }

    /** Preset: look at the nearest player within [range]. */
    public fun lookAtNearestPlayer(range: Double = 8.0) {
        movement = false
        canStart { nearbyPlayers(range).isNotEmpty() }
        tick { nearbyPlayers(range).firstOrNull()?.let(::lookAt) }
    }

    /** Preset: follow the target until [distance] blocks. */
    public fun chaseTarget(speed: Double = 1.0, distance: Double = 2.0) {
        canStart { target?.let { distanceTo(it) > distance } ?: false }
        tick { target?.let { moveTo(it, speed); lookAt(it) } }
        onStop { stopMoving() }
    }
}

/** A step of a [GoalBuilder.sequence]. */
public sealed interface SequenceAction {
    public class Run(public val action: EntityContext.() -> Unit) : SequenceAction
    public class Wait(public val ticks: Int) : SequenceAction
}

public class SequenceBuilder {
    public val steps: MutableList<SequenceAction> = ArrayList()

    public fun run(action: EntityContext.() -> Unit) {
        steps += SequenceAction.Run(action)
    }

    public fun wait(ticks: Int) {
        require(ticks >= 0) { "wait needs ticks >= 0" }
        steps += SequenceAction.Wait(ticks)
    }

    public fun animate(name: String, playback: AnimationPlayback = AnimationPlayback.ONCE, speed: Float = 1f) {
        run { animate(name, playback, speed) }
    }

    public fun stopMoving() {
        run { stopMoving() }
    }
}

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
