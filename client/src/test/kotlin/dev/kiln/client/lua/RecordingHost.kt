package dev.kiln.client.lua

import dev.kiln.client.lua.sandbox.LuaScriptException

/** Records everything the extension asks of the host. */
class RecordingHost : ExtensionHost {
    val sent = ArrayList<Pair<String, String>>()
    val toasts = ArrayList<Pair<String, String>>()
    val keys = ArrayList<Triple<String, String, String>>()
    val failures = ArrayList<String>()
    val logs = ArrayList<String>()
    var canSend = true
    var time = 0.0

    override fun send(channel: String, json: String): Boolean = canSend.also { if (it) sent += channel to json }
    override fun toast(extension: LuaExtension, title: String, text: String) { toasts += title to text }
    override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) { keys += Triple(id, name, defaultKey) }
    override fun time(): Double = time
    override fun player(): PlayerSnapshot = PlayerSnapshot("Steve", 1.0, 64.0, -3.5)
    override fun log(extension: LuaExtension, message: String) { logs += message }
    override fun failed(extension: LuaExtension, error: LuaScriptException) { failures += error.message ?: "" }
}
