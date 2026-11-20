package dev.kiln.client.lua

import com.google.gson.JsonParser
import dev.kiln.client.ext.ExtensionPackage
import java.io.File

/** The extensions of the example server (`example/server/src/main/resources/extensions/`), read like the server packs them. */
object ExampleExtensions {
    fun dir(id: String): File {
        val candidates = listOf(File("../example/server/src/main/resources/extensions/$id"), File("example/server/src/main/resources/extensions/$id"))
        return candidates.firstOrNull { it.isDirectory } ?: error("extension $id not found from ${File(".").absolutePath}")
    }

    /** Permissions and dependencies from `extension.json` (dependencies may be ids or `{id, version}` objects). */
    fun pkg(id: String): ExtensionPackage {
        val dir = dir(id)
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val json = JsonParser.parseString(files.getValue("extension.json").toString(Charsets.UTF_8)).asJsonObject
        val permissions = json.getAsJsonArray("permissions").map { it.asString }.toSet()
        val deps = json.getAsJsonArray("dependencies")?.map { if (it.isJsonObject) it.asJsonObject["id"].asString else it.asString } ?: emptyList()
        return ExtensionPackage(id, json["version"].asString, json["name"].asString, "main.lua", permissions, files, deps)
    }
}
