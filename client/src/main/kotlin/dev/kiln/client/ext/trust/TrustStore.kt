package dev.kiln.client.ext.trust

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The player's consent to extensions, per server address (`config/kiln-trust.json`). A server's request is
 * trusted when every requested (id, permissions) pair has been accepted before for that address; a new extension or
 * a changed permission set asks again (and the answer covers the whole set).
 *
 * ```json
 * { "version": 1, "servers": { "play.example.net": [ { "id": "factory", "permissions": ["hud", "widgets"] } ] } }
 * ```
 *
 * The file may be edited or deleted while the game runs: every read and change first reloads it if its modification
 * time or size differ from what was last read or written ([reloadIfChanged]), so an external change takes effect at
 * the next check instead of after a restart (and is not overwritten with stale data by the next [save]).
 */
class TrustStore(private val file: Path) {
    private val servers = LinkedHashMap<String, MutableSet<TrustGrant>>()

    /** File state as last read or written; `null` = never loaded. */
    private var stamp: FileStamp? = null

    /** Incremented on every load and change; screens use it to notice that the content changed. */
    var revision: Int = 0
        private set

    /** Reloads the file if it changed on disk since it was last read or written (or was never loaded). */
    fun reloadIfChanged() {
        if (stamp == null || FileStamp.of(file) != stamp) load()
    }

    fun isTrusted(server: String, request: Collection<TrustGrant>): Boolean {
        reloadIfChanged()
        val granted = servers[server] ?: return request.isEmpty()
        return request.all { it in granted }
    }

    /** Snapshot of every server with stored consent (in file order). */
    fun servers(): Map<String, Set<TrustGrant>> {
        reloadIfChanged()
        return servers.mapValuesTo(LinkedHashMap()) { (_, grants) -> LinkedHashSet(grants) }
    }

    /** Revokes the consent stored for [server] and saves immediately; `false` if there was none. */
    fun remove(server: String): Boolean {
        reloadIfChanged()
        if (servers.remove(server) == null) return false
        revision++
        save()
        return true
    }

    /** Revokes every stored consent and saves immediately; returns the servers that had one. */
    fun removeAll(): Set<String> {
        reloadIfChanged()
        val removed = LinkedHashSet(servers.keys)
        servers.clear()
        revision++
        save()
        return removed
    }

    fun trust(server: String, request: Collection<TrustGrant>) {
        reloadIfChanged()
        revision++
        val granted = servers.getOrPut(server) { LinkedHashSet() }
        // a re-grant for the same id replaces older permission sets of that id
        val ids = request.map { it.id }.toSet()
        granted.removeIf { it.id in ids }
        granted.addAll(request)
    }

    fun load() {
        servers.clear()
        revision++
        // taken before reading: a change while reading is noticed by the next check
        stamp = FileStamp.of(file)
        if (!Files.isRegularFile(file)) return
        try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
            val serversJson = root.getAsJsonObject("servers") ?: return
            for ((server, grantsJson) in serversJson.entrySet()) {
                if (!grantsJson.isJsonArray) continue
                val grants = grantsJson.asJsonArray.mapNotNullTo(LinkedHashSet(), ::readGrant)
                if (grants.isNotEmpty()) servers[server] = grants
            }
        } catch (e: Exception) {
            logger.warn("Could not read {}, every server asks again", file, e)
            servers.clear()
        }
    }

    /** One stored grant, or `null` if [json] is not a grant with an id. */
    private fun readGrant(json: JsonElement): TrustGrant? {
        if (!json.isJsonObject) return null
        val o = json.asJsonObject
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val permissions = o.get("permissions")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { p -> p.takeIf { it.isJsonPrimitive }?.asString }?.toSet() ?: emptySet()
        return TrustGrant(id, permissions)
    }

    fun save() {
        try {
            writeAtomically(GSON.toJson(toJson()))
            stamp = FileStamp.of(file)
        } catch (e: Exception) {
            logger.warn("Could not write {}", file, e)
        }
    }

    private fun toJson(): JsonObject {
        val serversJson = JsonObject()
        for ((server, grants) in servers) {
            val array = JsonArray()
            for (grant in grants) {
                val o = JsonObject()
                o.addProperty("id", grant.id)
                val permissions = JsonArray()
                grant.permissions.sorted().forEach(permissions::add)
                o.add("permissions", permissions)
                array.add(o)
            }
            serversJson.add(server, array)
        }
        val root = JsonObject()
        root.addProperty("version", 1)
        root.add("servers", serversJson)
        return root
    }

    /** Writes a temporary file next to [file] and moves it over (atomically where the file system can). */
    private fun writeAtomically(content: String) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("kiln")
        val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }
}
