package dev.kiln.server.api.item

import dev.kiln.server.api.Registration
import io.papermc.paper.datacomponent.DataComponentType
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
import org.bukkit.plugin.Plugin

/**
 * Custom items: item stacks of a vanilla [CustomItemBuilder.base] type with data components (above all
 * `item_model`, pointing at an item definition in an extension's `assets/<ns>/items/`). They are not registry
 * entries; Kiln recognises them by a persistent data key and dispatches the [CustomItemBuilder] hooks.
 */
public interface CustomItems {
    /**
     * Registers the custom item [key]. Its namespace must be the id of an extension registered by [plugin], and
     * the model ([CustomItemBuilder.model], default [key]) must have `assets/<ns>/items/<path>.json` in it.
     * Removed when [plugin] is disabled.
     *
     * @throws IllegalArgumentException wrong namespace or missing item definition.
     * @throws IllegalStateException [key] is already registered.
     */
    public fun register(plugin: Plugin, key: Key, init: CustomItemBuilder.() -> Unit): CustomItem

    /** The custom item [stack] is an instance of, if any. */
    public fun of(stack: ItemStack?): CustomItem?

    public fun get(key: Key): CustomItem?

    public fun all(): Collection<CustomItem>
}

/** A registered custom item. */
public interface CustomItem : Registration {
    public val key: Key
    public val plugin: Plugin

    /** A new stack of [amount] (1..max stack size) with every component of the definition. */
    public fun create(amount: Int = 1): ItemStack

    /** `true` if [stack] is an instance of this item. */
    public fun matches(stack: ItemStack?): Boolean
}

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

/** Data components applied to every created stack, in order. */
public class ItemComponents {
    private val actions = ArrayList<(ItemStack) -> Unit>()

    public fun <T : Any> set(type: DataComponentType.Valued<T>, value: T) {
        actions += { it.setData(type, value) }
    }

    public fun set(type: DataComponentType.NonValued) {
        actions += { it.setData(type) }
    }

    /** Removes a component the base item has by default. */
    public fun unset(type: DataComponentType) {
        actions += { it.unsetData(type) }
    }

    /** Applies every component to [stack]. */
    public fun applyTo(stack: ItemStack) {
        for (action in actions) action(stack)
    }
}
