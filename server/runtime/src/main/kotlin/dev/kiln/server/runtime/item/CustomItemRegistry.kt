package dev.kiln.server.runtime.item

import dev.kiln.server.api.item.CustomItem
import dev.kiln.server.api.item.CustomItemBuilder
import dev.kiln.server.api.item.CustomItems
import dev.kiln.server.runtime.extension.ExtensionImpl
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/** [CustomItems] on top of [CustomItemCatalog]; stacks carry their key in the PDC entry [ITEM_KEY]. */
class CustomItemRegistry(
    private val logger: Logger,
    extensions: (String) -> ExtensionImpl?,
) : CustomItems {
    private val catalog = CustomItemCatalog<CustomItemImpl> { id -> extensions(id)?.asContentExtension() }

    override fun register(plugin: Plugin, key: Key, init: CustomItemBuilder.() -> Unit): CustomItem {
        val definition = CustomItemBuilder().apply(init)
        val item = CustomItemImpl(this, plugin, key, definition)
        catalog.add(plugin.name, key, definition.model, item)
        return item
    }

    override fun of(stack: ItemStack?): CustomItem? {
        if (stack == null || stack.isEmpty) return null
        val raw = stack.persistentDataContainer.get(ITEM_KEY, PersistentDataType.STRING) ?: return null
        return Key.parseable(raw).takeIf { it }?.let { catalog.get(Key.key(raw)) }
    }

    override fun get(key: Key): CustomItem? = catalog.get(key)

    override fun all(): Collection<CustomItem> = catalog.all()

    fun removeAll(plugin: Plugin) = catalog.removeOwner(plugin.name)

    internal fun remove(item: CustomItemImpl) = catalog.remove(item.key, item)

    fun removeNamespace(namespace: String) {
        catalog.removeNamespace(namespace)
    }

    /** Runs a plugin hook; its exceptions are logged, never thrown into the event. */
    internal inline fun hook(item: CustomItem, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "Custom item ${item.key.asString()} ($what) of ${item.plugin.name} failed", e)
        }
    }

    companion object {
        val ITEM_KEY: NamespacedKey = NamespacedKey("kiln", "item")
    }
}
