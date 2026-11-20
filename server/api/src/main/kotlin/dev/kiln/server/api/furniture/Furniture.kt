package dev.kiln.server.api.furniture

import dev.kiln.server.api.Registration
import dev.kiln.server.api.item.CustomItem
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Furniture: a model (item display) plus hit boxes (interaction entities), placed in the world and stored with its
 * chunk. Solid hit boxes collide like boats for players and entities (mob path finding does not avoid them).
 * Without Kiln nothing of it remains visible; the data stays in the chunks.
 */
public interface FurnitureRegistry {
    /**
     * Registers furniture [key] (namespace = an extension of [plugin]; the model needs an item definition
     * `assets/<ns>/items/<path>.json` in it). Placed furniture of this type appears when it is registered and
     * disappears (but stays stored) when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace, missing model or invalid hit box.
     * @throws IllegalStateException [key] is already registered.
     */
    public fun register(plugin: Plugin, key: Key, init: FurnitureBuilder.() -> Unit): FurnitureType

    public fun get(key: Key): FurnitureType?

    /** The furniture [entity] (its model or one of its hit boxes) belongs to. */
    public fun at(entity: Entity): Furniture?
}

public interface FurnitureType : Registration {
    public val key: Key
    public val plugin: Plugin

    /**
     * Places this furniture with its origin (bottom centre) at [location]; [yaw] is snapped to 90° when the type has
     * hit boxes. Does not check for blocks in the way.
     */
    public fun place(location: Location, yaw: Float = location.yaw): Furniture
}

/** A placed piece of furniture. */
public interface Furniture {
    public val type: FurnitureType
    public val id: UUID

    /** Origin (bottom centre); its yaw is the furniture's rotation. */
    public val location: Location

    /** Removes it from the world (and its chunk); [dropItem] drops the type's item. */
    public fun remove(dropItem: Boolean = false)

    /** Lets [player] sit at [offset] (rotated with the furniture) until they dismount. */
    public fun seat(player: Player, offset: Vector)
}

/** Definition of a [FurnitureType]. */
public class FurnitureBuilder {
    /** Item model shown by the furniture's item display; `null` = the key. */
    public var model: Key? = null

    /** Item that places this furniture (right click on a block) and that breaking it drops; optional. */
    public var item: CustomItem? = null

    /** Height of the model's centre above the origin (a block-sized model: 0.5). */
    public var modelHeight: Double = 0.5

    internal val boxes = ArrayList<FurnitureHitbox>()

    public val hitboxes: List<FurnitureHitbox> get() = boxes

    public var onInteract: ((player: Player, furniture: Furniture) -> Unit)? = null

    /** Default: remove the furniture, dropping its item unless the player is in creative mode. */
    public var onBreak: ((player: Player, furniture: Furniture) -> Unit)? = null

    /**
     * A hit box: square base [width] × [height] blocks, its bottom centre at [offset] from the origin as seen when
     * the furniture faces south (+z = front). [solid] boxes can be stood on and block movement.
     */
    public fun hitbox(width: Double, height: Double, offset: Vector = Vector(), solid: Boolean = false) {
        require(width in 0.05..16.0 && height in 0.05..16.0) { "hit box size must be 0.05..16 blocks" }
        require(maxOf(kotlin.math.abs(offset.x), kotlin.math.abs(offset.y), kotlin.math.abs(offset.z)) <= 16.0) { "hit box offset must be within 16 blocks" }
        boxes += FurnitureHitbox(width, height, offset.clone(), solid)
    }

    public fun onInteract(handler: (player: Player, furniture: Furniture) -> Unit) {
        onInteract = handler
    }

    public fun onBreak(handler: (player: Player, furniture: Furniture) -> Unit) {
        onBreak = handler
    }
}

public data class FurnitureHitbox(val width: Double, val height: Double, val offset: Vector, val solid: Boolean)
