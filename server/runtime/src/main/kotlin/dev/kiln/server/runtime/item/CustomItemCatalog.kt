package dev.kiln.server.runtime.item

import net.kyori.adventure.key.Key

/**
 * Registered content of one kind ([V]) by key, with the ownership and asset checks every content kind shares:
 * the key's namespace is an extension registered by the same plugin, and the referenced asset exists in it.
 * Pure (plugin names instead of Bukkit objects), so it is testable without a server.
 */
class CustomItemCatalog<V>(private val extensions: (id: String) -> ContentExtension?) {
    private val entries = LinkedHashMap<Key, Pair<String, V>>()

    /**
     * Adds [value] under [key] for plugin [ownerName]; [model] (default [key]) must have an item definition
     * `assets/<ns>/items/<path>.json` in the extension [key]'s namespace names.
     */
    fun add(ownerName: String, key: Key, model: Key?, value: V) {
        val extension = owned(ownerName, key)
        val itemModel = modelOf(key, model)
        val definition = "assets/${itemModel.namespace()}/items/${itemModel.value()}.json"
        require(itemModel.namespace() == key.namespace() && definition in extension.files) {
            "Item model ${itemModel.asString()} not found in extension ${key.namespace()} (expected $definition)"
        }
        check(key !in entries) { "Custom item ${key.asString()} is already registered" }
        entries[key] = ownerName to value
    }

    fun get(key: Key): V? = entries[key]?.second

    fun all(): List<V> = entries.values.map { it.second }

    /** Removes [key] only while it still maps to [value] (a stale handle cannot remove a newer registration). */
    fun remove(key: Key, value: V) {
        if (entries[key]?.second === value) entries.remove(key)
    }

    /** Everything in extension namespace [namespace] (the extension was unregistered; its assets are gone). */
    fun removeNamespace(namespace: String): List<V> {
        val removed = entries.filterKeys { it.namespace() == namespace }
        removed.keys.forEach(entries::remove)
        return removed.values.map { it.second }
    }

    fun removeOwner(ownerName: String) {
        entries.values.removeIf { it.first == ownerName }
    }

    private fun owned(ownerName: String, key: Key): ContentExtension {
        val extension = extensions(key.namespace())
            ?: throw IllegalArgumentException("No extension '${key.namespace()}' registered for ${key.asString()} (register the extension first)")
        require(extension.ownerName == ownerName) {
            "${key.asString()}: extension '${key.namespace()}' belongs to ${extension.ownerName}, not to $ownerName"
        }
        return extension
    }

    companion object {
        fun modelOf(key: Key, model: Key?): Key = model ?: key
    }
}
