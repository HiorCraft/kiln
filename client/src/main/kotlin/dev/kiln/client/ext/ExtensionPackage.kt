package dev.kiln.client.ext

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.kiln.protocol.AssetRules
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.Ids
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * A verified extension package, read entirely into memory. Nothing of it is ever written to disk under its entry
 * names (the cache stores the zip as `<sha256>.zip`), so path traversal ("zip slip") cannot touch the file system;
 * the names are validated anyway because they become resource identifiers and `kiln.require` targets.
 */
class ExtensionPackage(
    val id: String,
    val version: String,
    val name: String,
    /** Lua entry point; `null` for a resource-only package. */
    val main: String?,
    val permissions: Set<String>,
    /** Path inside the package (`main.lua`, `textures/a/b.png`, ...) -> content. */
    val files: Map<String, ByteArray>,
    /** Ids of `extension.json` `dependencies` (strings or `{"id", "version"}` objects; versions are the server's job). */
    val dependencies: List<String> = emptyList(),
) {
    fun file(path: String): ByteArray? = files[path]

    /** The resource-pack tree: paths relative to `assets/` (`<id>/models/...`) -> content. */
    val assets: Map<String, ByteArray> by lazy {
        files.filterKeys { it.startsWith(AssetRules.ROOT) }.mapKeys { it.key.removePrefix(AssetRules.ROOT) }
    }

    companion object {
        const val MANIFEST = "extension.json"
        const val MAX_ZIP_BYTES = ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES
        const val MAX_FILES = ProtocolLimits.MAX_EXTENSION_FILES
        const val MAX_UNPACKED_BYTES = ProtocolLimits.MAX_EXTENSION_UNPACKED_BYTES.toLong()
        const val MAX_MANIFEST_BYTES = 64 * 1024

        private val SEGMENT = Regex("[a-z0-9_.-]+")
        private val ALLOWED_EXTENSIONS = setOf("lua", "png", "json")

        /** `true` if [path] is a permitted entry name: `[a-z0-9_.-]` segments separated by `/`, allowed suffix. */
        fun isValidPath(path: String): Boolean {
            if (path.isEmpty() || path.length > 256 || path.startsWith("/") || path.endsWith("/")) return false
            val segments = path.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || !SEGMENT.matches(it) }) return false
            val ext = path.substringAfterLast('.', "")
            val allowed = if (path.startsWith(AssetRules.ROOT)) AssetRules.EXTENSIONS else ALLOWED_EXTENSIONS
            return ext in allowed && segments.last().length > ext.length + 1
        }

        /**
         * Reads and validates a package zip. Sizes are enforced while inflating (header sizes are not trusted), so
         * a zip bomb stops at [MAX_UNPACKED_BYTES]. Rejects duplicate entries, forbidden names and suffixes, more
         * than [MAX_FILES] files and invalid `extension.json` content.
         */
        fun read(zip: ByteArray, expectedId: String? = null): ExtensionPackage {
            if (zip.size > MAX_ZIP_BYTES) fail("package is larger than ${MAX_ZIP_BYTES / 1024 / 1024} MiB")
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory) {
                            if (!isValidPath(name.trimEnd('/') + "/x.lua")) fail("invalid directory entry '${printable(name)}'")
                            continue
                        }
                        if (!isValidPath(name)) fail("forbidden file name '${printable(name)}'")
                        if (name in files) fail("duplicate file '$name'")
                        if (files.size >= MAX_FILES) fail("more than $MAX_FILES files")
                        val out = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UNPACKED_BYTES) fail("unpacked content is larger than ${MAX_UNPACKED_BYTES / 1024 / 1024} MiB")
                            out.write(buffer, 0, n)
                        }
                        files[name] = out.toByteArray()
                    }
                }
            } catch (e: ZipException) {
                fail("corrupt zip: ${e.message}")
            } catch (e: IOException) {
                fail("cannot read zip: ${e.message}")
            }
            return fromFiles(files, expectedId)
        }

        private fun fromFiles(files: Map<String, ByteArray>, expectedId: String?): ExtensionPackage {
            val manifestBytes = files[MANIFEST] ?: fail("$MANIFEST missing")
            if (manifestBytes.size > MAX_MANIFEST_BYTES) fail("$MANIFEST too large")
            val json = try {
                JsonParser.parseString(String(manifestBytes, Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                fail("$MANIFEST is not a JSON object")
            }
            val id = json.string("id") ?: fail("$MANIFEST: id missing")
            if (!Ids.isValidLuaExtensionId(id)) fail("$MANIFEST: invalid id '${printable(id)}'")
            Ids.reservedNamespaceProblem(id)?.let { fail("$MANIFEST: $it") }
            if (expectedId != null && id != expectedId) fail("$MANIFEST: id '$id' does not match the announced id '$expectedId'")
            val version = json.string("version") ?: fail("$MANIFEST: version missing")
            if (version.isEmpty() || version.length > 64) fail("$MANIFEST: version must have 1-64 characters")
            val name = json.string("name") ?: fail("$MANIFEST: name missing")
            if (name.isEmpty() || name.length > 128) fail("$MANIFEST: name must have 1-128 characters")
            // resource-only packages have no Lua: an explicit main must exist, the default main.lua is optional
            val main = if (json.has("main")) json.string("main") ?: fail("$MANIFEST: main must be a string") else "main.lua".takeIf { it in files }
            if (main != null && (!main.endsWith(".lua") || main !in files)) fail("$MANIFEST: main file '${printable(main)}' missing")
            val permissions = LinkedHashSet<String>()
            json.get("permissions")?.let { element ->
                if (!element.isJsonArray) fail("$MANIFEST: permissions must be an array")
                for (p in element.asJsonArray) {
                    val perm = if (p.isJsonPrimitive && p.asJsonPrimitive.isString) p.asString else fail("$MANIFEST: permissions must be strings")
                    if (perm !in ExtensionPermissions.ALL) fail("$MANIFEST: unknown permission '${printable(perm)}'")
                    if (!permissions.add(perm)) fail("$MANIFEST: duplicate permission '$perm'")
                }
            }
            checkAssets(id, files, permissions)
            val dependencies = ArrayList<String>()
            json.get("dependencies")?.let { element ->
                if (!element.isJsonArray) fail("$MANIFEST: dependencies must be an array")
                for (d in element.asJsonArray) {
                    val depId = when {
                        d.isJsonPrimitive && d.asJsonPrimitive.isString -> d.asString
                        d.isJsonObject -> d.asJsonObject.string("id") ?: fail("$MANIFEST: dependency objects need a string id")
                        else -> fail("$MANIFEST: dependencies must be ids or objects")
                    }
                    if (!Ids.isValidLuaExtensionId(depId) || depId == id || depId in dependencies) fail("$MANIFEST: invalid dependency '${printable(depId)}'")
                    dependencies += depId
                }
            }
            return ExtensionPackage(id, version, name, main, permissions, files, dependencies)
        }

        /** The `assets/` tree: own namespace, allowed types and sizes, no `kiln:` references, own language keys. */
        private fun checkAssets(id: String, files: Map<String, ByteArray>, permissions: Set<String>) {
            var any = false
            for ((path, bytes) in files) {
                if (!path.startsWith(AssetRules.ROOT)) continue
                any = true
                AssetRules.problem(id, path, bytes.size)?.let { fail(it) }
                if (path.endsWith(".png")) {
                    // size from the header before Minecraft decodes it (no huge allocations, no failing atlas)
                    val size = PngHeader.size(bytes) ?: fail("$path is not a PNG")
                    val max = ProtocolLimits.MAX_ASSET_TEXTURE_SIZE
                    if (size[0] !in 1..max || size[1] !in 1..max) fail("$path is ${size[0]}x${size[1]}, at most ${max}x$max allowed")
                }
                if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                    val text = String(bytes, Charsets.UTF_8)
                    AssetRules.jsonProblem(path, text)?.let { fail(it) }
                    if (path.startsWith("${AssetRules.ROOT}$id/lang/") && path.endsWith(".json")) {
                        val keys = try {
                            JsonParser.parseString(text).asJsonObject.keySet()
                        } catch (e: Exception) {
                            fail("$path is not a JSON object")
                        }
                        keys.firstOrNull { !AssetRules.langKeyAllowed(id, it) }?.let { fail("$path: translation key '${printable(it)}' must start with '$id.'") }
                    }
                }
            }
            if (any && ExtensionPermissions.RESOURCES !in permissions) fail("$MANIFEST: assets/ needs the permission 'resources'")
        }

        private fun JsonObject.string(key: String): String? {
            val e = get(key) ?: return null
            return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else null
        }

        private fun printable(s: String): String = s.take(64).map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("")

        private fun fail(message: String): Nothing = throw ExtensionPackageException(message)
    }
}
