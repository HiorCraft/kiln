package dev.kiln.server.api.furniture

import dev.kiln.server.api.item.CustomItem
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.abs

/** Definition of a [FurnitureType]. */
public class FurnitureBuilder {
    /** Item model shown by the furniture's item display; `null` = the key. */
    public var model: Key? = null

    /** Item that places this furniture (right click on a block) and that breaking it drops; optional. */
    public var item: CustomItem? = null

    /** Height of the model's centre above the origin (a block-sized model: 0.5). */
    public var modelHeight: Double = 0.5

    internal val boxes = ArrayList<FurnitureHitbox>()
    internal val seatOffsets = ArrayList<Vector>()

    /** Seats (see [seat]); a right click sits on the free one closest to the clicked point unless [onInteract] is set. */
    public val seats: List<Vector> get() = seatOffsets

    public val hitboxes: List<FurnitureHitbox> get() = boxes

    public var onInteract: ((player: Player, furniture: Furniture) -> Unit)? = null

    /** Default: remove the furniture, dropping its item unless the player is in creative mode. */
    public var onBreak: ((player: Player, furniture: Furniture) -> Unit)? = null

    /**
     * A hit box: square base [width] × [height] blocks, its bottom centre at [offset] from the origin as seen when
     * the furniture faces south (+z = front). [solid] boxes can be stood on and block movement.
     */
    public fun hitbox(width: Double, height: Double, offset: Vector = Vector(), solid: Boolean = false) {
        require(width in HITBOX_SIZE && height in HITBOX_SIZE) { "hit box size must be 0.05..16 blocks" }
        require(isWithinReach(offset)) { "hit box offset must be within 16 blocks" }
        boxes += FurnitureHitbox(width, height, offset.clone(), solid)
    }

    /**
     * A seat at [offset] from the origin (front = south, rotated with the furniture); `y` is the height of the
     * sitting surface. One player per seat.
     */
    public fun seat(offset: Vector) {
        require(isWithinReach(offset)) { "seat offset must be within 16 blocks" }
        seatOffsets += offset.clone()
    }

    public fun onInteract(handler: (player: Player, furniture: Furniture) -> Unit) {
        onInteract = handler
    }

    public fun onBreak(handler: (player: Player, furniture: Furniture) -> Unit) {
        onBreak = handler
    }

    private fun isWithinReach(offset: Vector): Boolean = maxOf(abs(offset.x), abs(offset.y), abs(offset.z)) <= MAX_OFFSET

    private companion object {
        val HITBOX_SIZE = 0.05..16.0

        /** Largest distance (per axis, in blocks) of a hit box or seat from the furniture's origin. */
        const val MAX_OFFSET = 16.0
    }
}
