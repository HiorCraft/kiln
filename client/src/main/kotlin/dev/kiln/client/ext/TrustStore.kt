package dev.kiln.client.ext

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
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
    data class Grant(val id: String, val permissions: Set<String>)

    private val servers = LinkedHashMap<String, MutableSet<Grant>>()

    /** File state as last read or written; `null` = never loaded. */
    private var stamp: Stamp? = null

    /** Incremented on every load and change; screens use it to notice that the content changed. */
    var revision: Int = 0
        private set

    private data class Stamp(val exists: Boolean, val modified: Long, val size: Long)

    private fun currentStamp(): Stamp = try {
        if (Files.isRegularFile(file)) Stamp(true, Files.getLastModifiedTime(file).toMillis(), Files.size(file))
        else Stamp(false, 0, 0)
    } catch (_: Exception) {
        Stamp(false, 0, 0)
    }

    /** Reloads the file if it changed on disk since it was last read or written (or was never loaded). */
    fun reloadIfChanged() {
        if (stamp == null || currentStamp() != stamp) load()
    }

    fun isTrusted(server: String, request: Collection<Grant>): Boolean {
        reloadIfChanged()
        val granted = servers[server] ?: return request.isEmpty()
        return request.all { it in granted }
    }

    /** Snapshot of every server with stored consent (in file order). */
    fun servers(): Map<String, Set<Grant>> {
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

    fun trust(server: String, request: Collection<Grant>) {
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
        stamp = currentStamp()
        if (!Files.isRegularFile(file)) return
        try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }.asJsonObject
            val serversJson = root.getAsJsonObject("servers") ?: return
            for ((server, grantsJson) in serversJson.entrySet()) {
                if (!grantsJson.isJsonArray) continue
                val grants = LinkedHashSet<Grant>()
                for (g in grantsJson.asJsonArray) {
                    if (!g.isJsonObject) continue
                    val o = g.asJsonObject
                    val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val perms = o.get("permissions")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { p -> p.takeIf { it.isJsonPrimitive }?.asString }?.toSet() ?: emptySet()
                    grants += Grant(id, perms)
                }
                if (grants.isNotEmpty()) servers[server] = grants
            }
        } catch (e: Exception) {
            logger.warn("Could not read {}, every server asks again", file, e)
            servers.clear()
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            root.addProperty("version", 1)
            val serversJson = JsonObject()
            for ((server, grants) in servers) {
                val array = JsonArray()
                for (grant in grants) {
                    val o = JsonObject()
                    o.addProperty("id", grant.id)
                    val perms = JsonArray()
                    grant.permissions.sorted().forEach(perms::add)
                    o.add("permissions", perms)
                    array.add(o)
                }
                serversJson.add(server, array)
            }
            root.add("servers", serversJson)
            file.parent?.let { Files.createDirectories(it) }
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, GSON.toJson(root))
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            stamp = currentStamp()
        } catch (e: Exception) {
            logger.warn("Could not write {}", file, e)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger("kiln")
        val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    }
}
