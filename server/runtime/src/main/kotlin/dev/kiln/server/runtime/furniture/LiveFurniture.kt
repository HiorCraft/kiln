package dev.kiln.server.runtime.furniture

import dev.kiln.server.api.furniture.Furniture
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

class LiveFurniture(
    private val manager: FurnitureManager,
    override val type: FurnitureTypeImpl,
    val stored: StoredFurniture,
    val display: ItemDisplay,
    val parts: List<Entity>,
    /** Network ids of the solid hit boxes. */
    val solidIds: Set<Int>,
) : Furniture {
    override val id: UUID get() = stored.id

    override val location: Location get() = Location(display.world, stored.x, stored.y, stored.z, stored.yaw, 0f)

    override fun remove(dropItem: Boolean) = manager.remove(this, dropItem)

    override fun seat(player: Player, offset: Vector) {
        manager.seat(this, player, offset)
    }

    override fun sit(player: Player, near: Location?): Boolean = manager.sit(this, player, near)
}
