package dev.kiln.client.keybind

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists the player's bindings of server-declared keybinds, per server and keybind id
 * (`config/kiln-keybinds.json`). Only bindings that differ from the server's default are stored, so a changed
 * server default still reaches players who never rebound the key.
 *
 * ```json
 * { "version": 1, "servers": { "play.example.net": { "factory:dash": "key.keyboard.g" } } }
 * ```
 */
class KeybindStore(private val file: Path) {
    private val servers = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private var dirty = false

    /** Stored key name for [keybindId] on [server], or `null` (use the default). */
    fun get(server: String, keybindId: String): String? = servers[server]?.get(keybindId)

    /** Remembers [keyName] for [keybindId] on [server]; [keyName] equal to [defaultKey] (or `null`) removes the entry. */
    fun set(server: String, keybindId: String, keyName: String?, defaultKey: String) {
        val bindings = servers.getOrPut(server) { LinkedHashMap() }
        val changed = if (keyName == null || keyName == defaultKey) {
            bindings.remove(keybindId) != null
        } else {
            bindings.put(keybindId, keyName) != keyName
        }
        if (bindings.isEmpty()) servers.remove(server)
        if (changed) dirty = true
    }

    fun load() {
        servers.clear()
        dirty = false
        if (!Files.isRegularFile(file)) return
        try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
            val serversJson = root.getAsJsonObject("servers") ?: return
            for ((server, bindingsJson) in serversJson.entrySet()) {
                if (!bindingsJson.isJsonObject) continue
                val bindings = LinkedHashMap<String, String>()
                for ((id, key) in bindingsJson.asJsonObject.entrySet()) {
                    if (key.isJsonPrimitive && key.asJsonPrimitive.isString) bindings[id] = key.asString
                }
                if (bindings.isNotEmpty()) servers[server] = bindings
            }
        } catch (e: Exception) {
            logger.warn("Could not read {}, starting with default keybinds", file, e)
            servers.clear()
        }
    }

    /** Writes the file if something changed since the last [load]/[save]. */
    fun save() {
        if (!dirty) return
        try {
            val root = JsonObject()
            root.addProperty("version", 1)
            val serversJson = JsonObject()
            for ((server, bindings) in servers) {
                val bindingsJson = JsonObject()
                bindings.forEach { (id, key) -> bindingsJson.addProperty(id, key) }
                serversJson.add(server, bindingsJson)
            }
            root.add("servers", serversJson)
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, GSON.toJson(root))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } catch (e: Exception) {
            logger.warn("Could not write {}", file, e)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("kiln")
        val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }
}
