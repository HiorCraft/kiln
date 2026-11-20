package dev.kiln.server.api.furniture

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/** A placed piece of furniture. */
public interface Furniture {
    public val type: FurnitureType
    public val id: UUID

    /** Origin (bottom centre); its yaw is the furniture's rotation. */
    public val location: Location

    /** Removes it from the world (and its chunk); [dropItem] drops the type's item. */
    public fun remove(dropItem: Boolean = false)

    /**
     * Lets [player] sit until they dismount; [offset] is the seat position relative to the origin (rotated with the
     * furniture), its `y` the height of the sitting surface. Dismounting stands the player up on the furniture.
     */
    public fun seat(player: Player, offset: Vector)

    /**
     * Seats [player] on the free seat ([FurnitureBuilder.seat]) closest to [near] (default: the player); `false` if
     * every seat is taken or the type has none.
     */
    public fun sit(player: Player, near: Location? = null): Boolean
}
