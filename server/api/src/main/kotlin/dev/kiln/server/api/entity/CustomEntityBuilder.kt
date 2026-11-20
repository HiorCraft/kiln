package dev.kiln.server.api.entity

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Entity

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
    public var onSpawn: (EntityContext.() -> Unit)? = null
    public var onDamage: (EntityContext.(source: Entity?, amount: Double) -> Unit)? = null
    public var onDeath: (EntityContext.() -> Unit)? = null

    /** Sounds of the entity (sound event keys, also from extension resources). */
    public fun sounds(block: EntitySounds.() -> Unit) {
        sounds.block()
    }

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
