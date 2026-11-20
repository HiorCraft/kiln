package dev.kiln.server.api.screen

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Passed to inventory grid click handlers: the clicked [slot] (player inventory numbering) and its item at render time. */
public class InventorySlotClickContext(
    public val click: ClickContext,
    public val slot: Int,
    /** Snapshot from the last render; read the inventory again before moving items. */
    public val item: ItemStack?,
) {
    public val player: Player get() = click.player
    public val screen: ScreenHandle get() = click.screen
    public val button: MouseButton get() = click.button
    public val shift: Boolean get() = click.shift
}
