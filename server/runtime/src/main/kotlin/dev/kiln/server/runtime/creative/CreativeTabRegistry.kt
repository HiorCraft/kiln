package dev.kiln.server.runtime.creative

import dev.kiln.protocol.message.CreativeTabData
import dev.kiln.protocol.message.CreativeTabs as CreativeTabsMessage
import dev.kiln.server.api.Registration
import dev.kiln.server.api.creative.CreativeTabBuilder
import dev.kiln.server.api.creative.CreativeTabs
import dev.kiln.server.runtime.extension.ExtensionImpl
import dev.kiln.server.runtime.screen.ScreenConverter
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.logging.Level
import java.util.logging.Logger

/** [CreativeTabs]: the catalog plus conversion into `CreativeTabs` messages (one entry per message). */
class CreativeTabRegistry(
    private val logger: Logger,
    extensions: (String) -> ExtensionImpl?,
    /** Called after every change (the runtime coalesces and resends). */
    private val onChanged: () -> Unit,
) : CreativeTabs {
    private val catalog = CreativeTabCatalog<ItemStack> { id -> extensions(id)?.asContentExtension() }

    override fun register(plugin: Plugin, key: Key, init: CreativeTabBuilder.() -> Unit): Registration {
        val builder = CreativeTabBuilder().apply(init)
        return registered(catalog.addTab(plugin.name, key, builder.title, builder.icon, builder::items))
    }

    override fun addToVanillaTab(plugin: Plugin, tab: Key, items: () -> List<ItemStack>): Registration {
        require(tab.namespace() == "minecraft") { "${tab.asString()} is no vanilla creative tab" }
        return registered(catalog.addToVanilla(plugin.name, tab, items))
    }

    private fun registered(entry: CreativeTabEntry<ItemStack>): Registration {
        onChanged()
        return Registration { catalog.remove(entry); onChanged() }
    }

    override fun refresh() = onChanged()

    fun removeNamespace(namespace: String) = notifyIfChanged { catalog.removeNamespace(namespace) }

    fun removeAll(plugin: Plugin) = notifyIfChanged { catalog.removeOwner(plugin.name) }

    private inline fun notifyIfChanged(change: () -> Unit) {
        val before = catalog.version
        change()
        if (catalog.version != before) onChanged()
    }

    /** The current content as messages: the first replaces the client's content, each carries one entry. */
    fun messages(): List<CreativeTabsMessage> {
        val tabs = catalog.entries().mapNotNull { entry ->
            try {
                tabData(entry)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Creative tab ${entry.id.asString()} of ${entry.owner} failed", e)
                null
            }
        }
        return batch(tabs)
    }

    private fun tabData(entry: CreativeTabEntry<ItemStack>): CreativeTabData {
        val items = entry.items().filterNot { it.isEmpty }.map { ScreenConverter.stack(it, it.amount) }
        val icon = (entry.icon?.invoke() ?: entry.items().firstOrNull())?.takeUnless { it.isEmpty }?.let { ScreenConverter.stack(it, 1) }
        return CreativeTabData(
            id = entry.id.asString(),
            title = ScreenConverter.json(entry.title as? Component ?: Component.empty()),
            icon = icon,
            items = items,
            target = entry.target?.asString(),
        )
    }

    companion object {
        /**
         * An empty reset first, then one entry per message: an entry that cannot be sent (too large) is skipped
         * without the client keeping old content.
         */
        fun batch(tabs: List<CreativeTabData>): List<CreativeTabsMessage> =
            listOf(CreativeTabsMessage(true, emptyList())) + tabs.map { CreativeTabsMessage(false, listOf(it)) }
    }
}
