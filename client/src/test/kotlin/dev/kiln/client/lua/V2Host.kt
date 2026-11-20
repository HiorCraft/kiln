package dev.kiln.client.lua

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.lua.items.InventorySlot
import dev.kiln.client.lua.items.ItemRef
import dev.kiln.client.lua.sandbox.LuaLimits
import dev.kiln.client.lua.sandbox.LuaScriptException
import dev.kiln.client.lua.screen.KeyResult
import dev.kiln.client.lua.screen.LuaScreenSession
import dev.kiln.client.lua.screen.ModifierKeys
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** A host with the Lua API v2 parts (extensions by id, screens shown immediately, fake items). */
class V2Host : ExtensionHost {
    val extensions = LinkedHashMap<String, LuaExtension>()
    val sent = ArrayList<Pair<String, String>>()
    val failures = ArrayList<String>()
    val logs = ArrayList<String>()
    val sounds = ArrayList<String>()
    var shown: LuaScreenSession? = null
    var view: FakeView = FakeView()
    var clockSeconds = 0.0
    var decodes = 0
    var inventorySlots: List<InventorySlot> = emptyList()
    /** Keybinds registered by the extensions: "<ext>:<id>" -> default key name. */
    val keybinds = LinkedHashMap<String, String>()

    fun load(pkg: ExtensionPackage, limits: LuaLimits = LuaLimits()): LuaExtension {
        val ext = LuaExtension(pkg, this, limits)
        extensions[pkg.id] = ext
        ext.load()
        return ext
    }

    override fun send(channel: String, json: String): Boolean { sent += channel to json; return true }
    override fun toast(extension: LuaExtension, title: String, text: String) {}
    override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) { keybinds["${extension.id}:$id"] = defaultKey }
    override fun time(): Double = 0.0
    override fun player(): PlayerSnapshot = PlayerSnapshot("Steve", 0.0, 0.0, 0.0)
    override fun log(extension: LuaExtension, message: String) { logs += message }
    override fun failed(extension: LuaExtension, error: LuaScriptException) { failures += "${extension.id}: ${error.message}" }
    override fun extension(id: String): LuaExtension? = extensions[id]
    override fun clock(): Double = clockSeconds
    override fun playSound(name: String, pitch: Float) { sounds += name }
    override fun playSound(id: String, volume: Float, pitch: Float) { sounds += "$id@$volume/$pitch" }
    override fun inventory(): List<InventorySlot> = inventorySlots

    override fun decodeItem(nbt: ByteArray): ItemRef? {
        decodes++
        val text = java.util.zip.GZIPInputStream(nbt.inputStream()).readBytes().toString(Charsets.UTF_8)
        if (!text.startsWith("item:")) return null
        return FakeItem(text.removePrefix("item:"))
    }

    /** Like the runtime: replacing a shown screen removes it first, then the new one is laid out (init). */
    override fun openScreen(session: LuaScreenSession): Boolean {
        shown?.let { old -> shown = null; old.extension.screenRemoved(old) }
        shown = session
        view = FakeView()
        session.view = view
        session.layout(400, 300, 2.0)
        return true
    }

    override fun closeScreen(session: LuaScreenSession) {
        if (shown !== session) return
        shown = null
        session.extension.screenRemoved(session)
    }

    /** Escape as the runtime handles it: Lua first, then close. */
    fun escape(shift: Boolean = false): KeyResult {
        val session = shown!!
        val result = session.keyPressed("escape", ModifierKeys(shift, false, false))
        if (result == KeyResult.CLOSE) closeScreen(session)
        return result
    }

    companion object {
        fun pkg(id: String, permissions: Set<String> = emptySet(), deps: List<String> = emptyList(), vararg files: Pair<String, String>) =
            ExtensionPackage(id, "1", id, "main.lua", permissions, files.associate { it.first to it.second.toByteArray() }, deps)

        /** What `ExtensionItems.encode` would send for a fake item [id]. */
        fun item(id: String): String {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write("item:$id".toByteArray()) }
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }
    }
}
