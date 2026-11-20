package dev.kiln.client.lua

import dev.kiln.client.lua.items.InventorySlot
import dev.kiln.client.lua.items.ItemRef
import dev.kiln.client.lua.sandbox.LuaScriptException
import dev.kiln.client.lua.screen.LuaScreenSession

/** What an extension needs from the client runtime (Minecraft side). All calls happen on the client thread. */
interface ExtensionHost {
    /** Sends [json] on channel `<ext>:<name>`; `false` if not possible right now (not in play, too large). */
    fun send(channel: String, json: String): Boolean

    fun toast(extension: LuaExtension, title: String, text: String)

    /** Registers keybind [id] of [extension]; edges are delivered through [LuaExtension.onKey]. */
    fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String)

    /** Game time in seconds (ticks / 20, with partial tick). */
    fun time(): Double

    fun player(): PlayerSnapshot?

    fun log(extension: LuaExtension, message: String)

    /** [extension] failed at runtime (Lua error or limit) and has stopped; the host unloads it. */
    fun failed(extension: LuaExtension, error: LuaScriptException)

    // ---- Lua API v2 (defaults: not available, e.g. in tests) ----

    /** A loaded extension by id (for `kiln.import`), or `null`. */
    fun extension(id: String): LuaExtension? = null

    /** Shows [session] (replacing any open screen; the replaced Lua screen gets `close(..., "replaced")`). */
    fun openScreen(session: LuaScreenSession): Boolean = false

    /** Closes [session] if it is the screen shown. */
    fun closeScreen(session: LuaScreenSession) {}

    /** Decodes gzip NBT of `ItemStack.serializeAsBytes()`; `null` if it is no valid item. */
    fun decodeItem(nbt: ByteArray): ItemRef? = null

    /** Non-empty slots of the local player's inventory (copies), or `null` without a player. */
    fun inventory(): List<InventorySlot>? = null

    /** Plays UI sound [name] (one of [LuaExtension.SOUNDS]) with [pitch]. */
    fun playSound(name: String, pitch: Float) {}

    /** Plays sound event [id] (`namespace:path`, from extension resources) as UI sound. */
    fun playSound(id: String, volume: Float, pitch: Float) {}

    /** Monotonic real time in seconds (animations). */
    fun clock(): Double = System.nanoTime() / 1e9

    fun viewport(): ViewportInfo? = null
}
