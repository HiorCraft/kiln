package dev.kiln.server.runtime.creative

import dev.kiln.protocol.ProtocolLimits
import dev.kiln.server.runtime.item.ContentExtension
import net.kyori.adventure.key.Key

/**
 * Creative-inventory entries in registration order: own tabs (at most [ProtocolLimits.MAX_CREATIVE_TABS], key in an
 * extension namespace of the owner) and additions to vanilla tabs. Items are produced lazily ([CreativeTabEntry.items]) so they
 * always reflect the current item definitions. Pure; [T] is the item type (ItemStack at runtime, String in tests).
 */
class CreativeTabCatalog<T>(private val extensions: (id: String) -> ContentExtension?) {
    private val entries = ArrayList<CreativeTabEntry<T>>()

    /** Increases with every change; the runtime resends when it differs from what players got. */
    var version: Long = 0
        private set

    fun addTab(owner: String, key: Key, title: Any?, icon: (() -> T)? = null, items: () -> List<T>): CreativeTabEntry<T> {
        val extension = extensions(key.namespace())
            ?: throw IllegalArgumentException("No extension '${key.namespace()}' registered for creative tab ${key.asString()}")
        require(extension.ownerName == owner) { "Creative tab ${key.asString()}: extension '${key.namespace()}' belongs to ${extension.ownerName}, not to $owner" }
        check(entries.none { it.target == null && it.id == key }) { "Creative tab ${key.asString()} is already registered" }
        check(entries.count { it.target == null } < ProtocolLimits.MAX_CREATIVE_TABS) {
            "At most ${ProtocolLimits.MAX_CREATIVE_TABS} creative tabs (ProtocolLimits.MAX_CREATIVE_TABS); ${key.asString()} is not registered"
        }
        return add(CreativeTabEntry(owner, key, null, title, icon, items))
    }

    fun addToVanilla(owner: String, tab: Key, items: () -> List<T>): CreativeTabEntry<T> =
        add(CreativeTabEntry(owner, tab, tab, null, null, items))

    private fun add(entry: CreativeTabEntry<T>): CreativeTabEntry<T> {
        check(entries.size < ProtocolLimits.MAX_CREATIVE_ENTRIES) { "At most ${ProtocolLimits.MAX_CREATIVE_ENTRIES} creative entries" }
        entries += entry
        version++
        return entry
    }

    fun remove(entry: CreativeTabEntry<T>) {
        if (entries.remove(entry)) version++
    }

    /** Own tabs of extension [namespace] (vanilla-tab additions are keyed by the vanilla tab and stay). */
    fun removeNamespace(namespace: String) {
        if (entries.removeIf { it.target == null && it.id.namespace() == namespace }) version++
    }

    fun removeOwner(owner: String) {
        if (entries.removeIf { it.owner == owner }) version++
    }

    fun entries(): List<CreativeTabEntry<T>> = entries.toList()
}
