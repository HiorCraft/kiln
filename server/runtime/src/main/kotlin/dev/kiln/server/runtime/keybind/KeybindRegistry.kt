package dev.kiln.server.runtime.keybind

import dev.kiln.server.api.keybind.KeyCode
import dev.kiln.server.api.keybind.Keybind
import dev.kiln.server.api.keybind.KeybindHandlers
import dev.kiln.protocol.Ids
import dev.kiln.protocol.message.KeybindDeclaration
import dev.kiln.protocol.ProtocolLimits
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * All server keybinds, keyed by wire id. [onChanged] runs after every registration/removal so connected clients
 * get a new `DeclareKeybinds`; [pressedLookup] answers [Keybind.isPressed] from the player sessions.
 */
class KeybindRegistry(
    val pressedLookup: (Player, String) -> Boolean,
    private val onChanged: () -> Unit,
) {
    private val keybinds = LinkedHashMap<String, KeybindImpl>()

    fun all(): Collection<KeybindImpl> = keybinds.values.toList()

    fun byWireId(id: String): KeybindImpl? = keybinds[id]

    fun declarations(): List<KeybindDeclaration> = keybinds.values.map { it.declaration() }

    /**
     * Registers (or, for the same plugin, replaces) a keybind.
     * @throws IllegalArgumentException for invalid values, a key owned by another plugin or too many keybinds.
     */
    fun register(plugin: Plugin, key: Key, name: String, defaultKey: KeyCode, category: String, handlers: KeybindHandlers): KeybindImpl {
        val wireId = key.asString()
        require(Ids.isValidKeybindId(wireId)) { "Invalid keybind key '$wireId' (allowed: [A-Za-z0-9_.:/-], 1..64 chars)" }
        require(name.isNotEmpty() && name.length <= ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH) { "Keybind name must be 1..${ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH} chars" }
        require(category.isNotEmpty() && category.length <= ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH) { "Keybind category must be 1..${ProtocolLimits.MAX_KEYBIND_TEXT_LENGTH} chars" }
        require(defaultKey.name.isNotEmpty() && defaultKey.name.length <= ProtocolLimits.MAX_ID_LENGTH) { "Invalid default key '$defaultKey'" }
        val existing = keybinds[wireId]
        require(existing == null || existing.plugin == plugin) {
            "Keybind '$wireId' is already registered by plugin ${existing!!.plugin.name}"
        }
        require(existing != null || keybinds.size < ProtocolLimits.MAX_KEYBINDS) { "Too many keybinds (max ${ProtocolLimits.MAX_KEYBINDS})" }
        val keybind = KeybindImpl(this, plugin, key, name, category, defaultKey, handlers)
        keybinds[wireId] = keybind
        onChanged()
        return keybind
    }

    fun remove(keybind: KeybindImpl) {
        if (keybinds[keybind.wireId] === keybind) {
            keybinds.remove(keybind.wireId)
            onChanged()
        }
    }

    /** Removes all keybinds of [plugin]. */
    fun removeAll(plugin: Plugin) {
        if (keybinds.values.removeIf { it.plugin == plugin }) onChanged()
    }
}
