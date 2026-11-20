package dev.kiln.server.api.item

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType

/** Definition of a [CustomItem]. */
public class CustomItemBuilder {
    /** Item model (`item_model` component); `null` = the item's key. */
    public var model: Key? = null

    /** Vanilla item the stack is made of (behaviour not overridden by components, e.g. placing). */
    public var base: ItemType = ItemType.PAPER

    /** Display name (`item_name` component). */
    public var name: Component? = null

    public var lore: List<Component> = emptyList()

    public val components: ItemComponents = ItemComponents()

    public var onUse: ((player: Player, stack: ItemStack, event: PlayerInteractEvent) -> Unit)? = null
    public var onHit: ((player: Player, target: Entity, stack: ItemStack, event: EntityDamageByEntityEvent) -> Unit)? = null
    public var onBreakBlock: ((player: Player, block: Block, stack: ItemStack, event: BlockBreakEvent) -> Unit)? = null

    /** More data components (Paper `DataComponentTypes`), e.g. `set(DataComponentTypes.MAX_STACK_SIZE, 16)`. */
    public fun components(block: ItemComponents.() -> Unit) {
        components.block()
    }

    /** Right click (air or block) with the item in either hand. */
    public fun onUse(handler: (player: Player, stack: ItemStack, event: PlayerInteractEvent) -> Unit) {
        onUse = handler
    }

    /** The holder hits an entity with the item in the main hand. */
    public fun onHit(handler: (player: Player, target: Entity, stack: ItemStack, event: EntityDamageByEntityEvent) -> Unit) {
        onHit = handler
    }

    /** The holder breaks a block with the item in the main hand. */
    public fun onBreakBlock(handler: (player: Player, block: Block, stack: ItemStack, event: BlockBreakEvent) -> Unit) {
        onBreakBlock = handler
    }
}
