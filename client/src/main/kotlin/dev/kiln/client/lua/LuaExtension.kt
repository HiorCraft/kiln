package dev.kiln.client.lua

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.lua.exports.ExportedFunction
import dev.kiln.client.lua.exports.GatedFunction
import dev.kiln.client.lua.exports.LuaExports
import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.graphics.LuaColor
import dev.kiln.client.lua.graphics.LuaGraphics
import dev.kiln.client.lua.items.ItemHandle
import dev.kiln.client.lua.items.LuaItems
import dev.kiln.client.lua.json.JsonLimits
import dev.kiln.client.lua.json.LuaJson
import dev.kiln.client.lua.sandbox.LuaCaller
import dev.kiln.client.lua.sandbox.LuaExecution
import dev.kiln.client.lua.sandbox.LuaLimits
import dev.kiln.client.lua.sandbox.LuaRuntime
import dev.kiln.client.lua.sandbox.LuaSandbox
import dev.kiln.client.lua.sandbox.LuaScriptException
import dev.kiln.client.lua.screen.LuaScreenSession
import dev.kiln.client.lua.screen.ScreenState
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolLimits
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import java.util.Collections
import java.util.WeakHashMap

/**
 * One loaded extension: its sandboxed Lua environment ([LuaRuntime]) plus the `kiln` API (v2) bound to it.
 *
 * Permissions from the manifest decide which API parts exist (`nil` otherwise):
 * `ui.widget`/`ui.tooltip` need `widgets`, `ui.toast` needs `toasts`, `hud` needs `hud`, `keys` needs `keybinds`,
 * `net` needs `channels`, `ui.screen/open/close/sound` need `screens`, `player().inventory` needs `inventory`;
 * `ui.lerpColor`, `ui.viewport`, `items`, `time`, `clock`, `player`, `require`, `export`, `import`, `readonly` are
 * always there.
 *
 * **No borrowing of permissions:** every permission-gated function requires the permission from this extension, from
 * the *principal* of the running call ([LuaExecution.principal], the extension the host called) and from the
 * extension whose code called it ([LuaExecution.caller]), see [checkCaller]: an extension without `channels` that
 * calls an imported function which sends on the API extension's channel gets a Lua error, and so does one that got
 * hold of another extension's raw `ch.send`. The principal also pays the instruction budget of everything that runs
 * in its call.
 *
 * After a runtime error the extension is dead ([alive] = false): nothing of it runs again, the host is notified once.
 */
class LuaExtension(
    val pkg: ExtensionPackage,
    private val host: ExtensionHost,
    limits: LuaLimits = LuaLimits(),
) {
    val id: String get() = pkg.id
    val name: String get() = pkg.name

    val runtime = LuaRuntime(pkg.id, limits, print = { host.log(this, it) })

    var alive: Boolean = true
        private set

    private var loading = false

    /** Ids of the extensions this one may import (manifest `dependencies`). */
    val dependencies: List<String> get() = pkg.dependencies

    /** Value given to `kiln.export` (`nil` = nothing exported). */
    var exported: LuaValue = LuaValue.NIL
        private set

    /** Read-only views of this extension's values (`kiln.readonly`, no holder). */
    val exports = LuaExports(this)

    private val importerViews = HashMap<LuaExtension, LuaExports>()

    /** Read-only views of this extension's export for [importer] (functions remember their holder). */
    fun exportsFor(importer: LuaExtension): LuaExports = importerViews.getOrPut(importer) { LuaExports(this, importer) }

    val items = LuaItems({ host.decodeItem(it) })

    private val screenDefs = HashMap<String, LuaTable>()

    /** This extension's Lua screen while it is shown. */
    var screenSession: LuaScreenSession? = null
        private set

    private var inCloseCallback = false

    /** [ExtensionHost.clock] until which `ui.open` refuses (after the player forced a screen of this extension shut). */
    private var openBlockedUntil = Double.NEGATIVE_INFINITY
    private val openLimit = SlidingWindowLimit(MAX_OPENS_PER_10S, 10 * NANOS_PER_SECOND)

    /** Shared by `ui.sound` / `s:sound` and `sound.play`. */
    private val soundLimit = SlidingWindowLimit(MAX_SOUNDS_PER_SECOND, NANOS_PER_SECOND)
    private val sendLimit = SlidingWindowLimit(MAX_SENDS_PER_SECOND, NANOS_PER_SECOND)
    private val toastLimit = SlidingWindowLimit(MAX_TOASTS_PER_10S, 10 * NANOS_PER_SECOND)

    /** Widget definitions by name (`<ext>:<name>` -> name). */
    private val widgetDefs = HashMap<String, LuaTable>()
    private val instances = Collections.newSetFromMap(WeakHashMap<WidgetInstance, Boolean>())

    private val hud = LinkedHashMap<String, HudLayer>()
    val hudLayers: Collection<HudLayer> get() = hud.values

    private val keyHandlers = HashMap<String, KeyHandlers>()
    private val channels = HashMap<String, ExtensionChannel>()
    private val modules = HashMap<String, LuaValue>()
    private val modulesLoading = HashSet<String>()

    internal val graphics = LuaGraphics(this)

    init {
        runtime.owner = this
        runtime.memoryDependencies = { dependencies.mapNotNull { host.extension(it)?.runtime } }
        runtime.extraRoots = ::memoryRoots
        runtime.globals.set("kiln", createApi())
    }

    /** Lua values only the host holds (definitions, state tables, callbacks): extra roots of the memory estimate. */
    private fun memoryRoots(): List<LuaValue> {
        val roots = ArrayList<LuaValue>()
        roots += exported
        roots += screenDefs.values
        screenSession?.let { session ->
            roots += session.self
            roots += session.screen
            session.textInputs.forEach { roots += it.field }
        }
        roots += widgetDefs.values
        synchronized(instances) { instances.forEach { roots += it.self } }
        hud.values.forEach { roots += it.render }
        keyHandlers.values.forEach { roots += it.press; roots += it.release }
        channels.values.forEach { roots += it.table; roots.addAll(it.handlers) }
        roots += modules.values
        return roots
    }

    private fun has(permission: String) = permission in pkg.permissions

    /**
     * [id] (`namespace:path`) of a resource this extension may use from Lua: needs `resources` and the namespace
     * of this extension or one of its dependencies (never vanilla, kiln or unrelated extensions).
     */
    internal fun ownResource(id: String, what: String): String {
        if (!has(ExtensionPermissions.RESOURCES)) throw LuaError("permission 'resources' missing: extension '${this.id}' cannot use $what")
        val namespace = id.substringBefore(':', "")
        val path = id.substringAfter(':', "")
        if (namespace.isEmpty() || path.isEmpty() || !RESOURCE_PATH.matches(path)) throw LuaError("invalid $what id '${id.take(64)}' (expected namespace:path)")
        if (namespace != this.id && namespace !in pkg.dependencies) {
            throw LuaError("namespace '$namespace' not allowed for $what (own or dependency only)")
        }
        return id
    }

    /**
     * Permission check of every gated API function of this extension; a Lua error unless **all** of these hold
     * [permission] (effective rights = intersection, a function never runs with more rights than its owner):
     * - this extension, the owner of the API function (always true for functions that exist, checked anyway);
     * - the principal of the running call ([LuaExecution.principal], the extension the host called); there must be
     *   one: outside a runtime call no gated function runs;
     * - the extension whose Lua code called the function directly ([LuaExecution.caller]): a raw API function handed
     *   to (or stolen by) another extension cannot be used by that extension's code, also not inside a call of this
     *   extension (e.g. a callback of the other extension that this one runs). The call must be direct: a `CALL` of
     *   Lua code or `pcall(f, ...)`; never as a metamethod (`__index`, `__tostring`, `__call`, ...) or through a
     *   library function (`tostring`, `table.sort`, ...), where the code that triggers it is not its caller;
     * - every importer holding an exported function that is running now ([ExportedFunction], [LuaExports.holder]):
     *   an imported function handed back to its exporter (callback, metamethod) does not lend the exporter's rights.
     *   A view without holder (`kiln.readonly`) counts as a holder without permissions (deny by default).
     *
     * [function] is the gated function itself (to recognise its direct call).
     */
    internal fun checkCaller(permission: String, function: LuaValue? = null) {
        if (!has(permission)) throw LuaError("permission '$permission' missing: extension '$id' does not have it")
        checkPrincipal(permission)
        checkHolders(permission)
        checkDirectCaller(permission, function)
    }

    private fun checkPrincipal(permission: String) {
        val principal = LuaExecution.principal?.owner as? LuaExtension
            ?: throw LuaError("permission '$permission': API of '$id' used outside a call of the runtime")
        if (principal !== this && permission !in principal.pkg.permissions) {
            throw LuaError("permission '$permission' missing: extension '${principal.id}' cannot use it through '$id'")
        }
    }

    private fun checkHolders(permission: String) {
        for (holder in LuaExecution.activeHolders) {
            if (holder === LuaExecution.UNKNOWN_HOLDER) {
                throw LuaError("permission '$permission': API of '$id' reached through a read-only view with unknown holder (kiln.readonly)")
            }
            if (holder is LuaExtension && holder !== this && permission !in holder.pkg.permissions) {
                throw LuaError("permission '$permission' missing: extension '${holder.id}' cannot use it through a function it imported from '$id'")
            }
        }
    }

    private fun checkDirectCaller(permission: String, function: LuaValue?) {
        val isTarget: (LuaValue) -> Boolean = { v -> function != null && (v === function || (v is ExportedFunction && v.wraps(function))) }
        when (val caller = LuaExecution.caller(isTarget)) {
            LuaCaller.Host -> {}
            LuaCaller.Indirect -> throw LuaError("permission '$permission': API functions of '$id' may only be called directly (not as a metamethod or through a library function)")
            LuaCaller.Unknown -> throw LuaError("permission '$permission': caller of '$id' API is unknown")
            is LuaCaller.Script -> {
                val owner = caller.runtime.owner as? LuaExtension
                    ?: throw LuaError("permission '$permission': caller of '$id' API is unknown")
                if (owner !== this && permission !in owner.pkg.permissions) {
                    throw LuaError("permission '$permission' missing: code of extension '${owner.id}' cannot use it through '$id'")
                }
            }
        }
    }

    // ---- loading ------------------------------------------------------------------------------------------

    /** Runs the main file (none for resource-only packages). Throws [LuaScriptException] (load failure = kick). */
    fun load() {
        val main = pkg.main ?: return // resource-only package: nothing to run
        val source = pkg.file(main) ?: throw LuaScriptException("$id: main file $main missing")
        loading = true
        try {
            runtime.load(source, main.removeSuffix(".lua"))
            runtime.checkMemory()
        } catch (e: LuaScriptException) {
            alive = false
            throw e
        } finally {
            loading = false
        }
    }

    /** Stops the extension for good (unload on disconnect or after an error elsewhere). */
    fun stop() {
        alive = false
        screenSession?.dispose()
        screenSession = null
    }

    /** Runs [function] with the per-call budget; on failure the extension dies and the host is told. */
    internal fun invoke(function: LuaValue, args: Varargs = LuaValue.NONE): Varargs? {
        if (!alive || !function.isfunction()) return null
        if (insideForeignCall()) return invokeInsideForeignCall(function, args)
        return try {
            runtime.call(function, args)
        } catch (e: LuaScriptException) {
            if (loading) throw e
            die(e)
            null
        }
    }

    /** Another extension's call is running (e.g. a screen replaced by its `ui.open` runs this one's close callback). */
    private fun insideForeignCall(): Boolean {
        val principal = LuaExecution.principal?.owner
        return principal != null && principal !== this && !loading
    }

    /** Budget limits stay the running call's, but a Lua error only stops this extension. */
    private fun invokeInsideForeignCall(function: LuaValue, args: Varargs): Varargs? = try {
        function.invoke(args)
    } catch (e: LuaError) {
        die(LuaScriptException("$id: ${(e.message ?: "error").lineSequence().first().take(300)}"))
        null
    }

    /** Like [invoke] with the per-frame budget of screen rendering (not counted in the one-second window). */
    internal fun invokeFrame(function: LuaValue, args: Varargs): Varargs? {
        if (!alive || !function.isfunction()) return null
        return try {
            runtime.call(function, args, runtime.limits.instructionsPerFrame, countInWindow = false)
        } catch (e: LuaScriptException) {
            die(e)
            null
        }
    }

    private fun die(e: LuaScriptException) {
        alive = false
        val session = screenSession
        session?.dispose()
        host.failed(this, e)
        if (session != null) host.closeScreen(session)
        screenSession = null
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    fun hasWidget(name: String): Boolean = name in widgetDefs

    /** A new instance of widget [name] (state table `self`, `init` already run), or `null` if unknown/dead. */
    fun createWidget(name: String): WidgetInstance? {
        if (!alive) return null
        val def = widgetDefs[name] ?: return null
        val instance = WidgetInstance(this, def)
        synchronized(instances) { instances += instance }
        invoke(def.rawget("init"), instance.self)
        return if (alive) instance else null
    }

    // ---- HUD ----------------------------------------------------------------------------------------------

    /** Draws [layer] into the area at ([x], [y]). */
    fun renderHud(layer: HudLayer, target: DrawTarget, x: Int, y: Int) {
        val area = LuaTable()
        area.set("width", layer.width)
        area.set("height", layer.height)
        graphics.draw(target, x, y, allowTooltip = false) {
            invoke(layer.render, LuaValue.varargsOf(graphics.table, area))
        }
    }

    // ---- keybinds -----------------------------------------------------------------------------------------

    fun onKey(id: String, pressed: Boolean) {
        val handlers = keyHandlers[id] ?: return
        invoke(if (pressed) handlers.press else handlers.release)
    }

    // ---- channels -----------------------------------------------------------------------------------------

    /** A message from the server on `<id>:<channel>`. Invalid JSON is dropped (logged), not a script error. */
    fun onChannelMessage(channel: String, json: String) {
        val ch = channels[channel] ?: return
        if (ch.handlers.isEmpty() || !alive) return
        val message = try {
            LuaJson.decode(json, INCOMING_JSON_LIMITS)
        } catch (e: LuaError) {
            host.log(this, "dropping invalid message on $id:$channel: ${e.message}")
            return
        }
        for (handler in ch.handlers.toList()) {
            if (!alive) return
            invoke(handler, message)
        }
    }

    // ---- API ----------------------------------------------------------------------------------------------

    private fun createApi(): LuaTable {
        val api = LuaTable()
        api.set("ui", createUiApi())
        if (has(ExtensionPermissions.HUD)) {
            api.set("hud", luaTable("layer" to gated(ExtensionPermissions.HUD) { args -> defineHud(args.checkjstring(1), args.checktable(2), args.checkfunction(3)); LuaValue.NONE }))
        }
        if (has(ExtensionPermissions.KEYBINDS)) {
            api.set("keys", luaTable("bind" to gated(ExtensionPermissions.KEYBINDS) { args -> bindKey(args.checkjstring(1), args.checktable(2)); LuaValue.NONE }))
        }
        if (has(ExtensionPermissions.CHANNELS)) {
            api.set("net", luaTable("channel" to gated(ExtensionPermissions.CHANNELS) { args -> channel(args.checkjstring(1)) }))
        }
        if (has(ExtensionPermissions.RESOURCES)) {
            api.set("sound", luaTable("play" to gated(ExtensionPermissions.RESOURCES) { args -> playResourceSound(args); LuaValue.NONE }))
        }
        api.set("items", luaTable("decode" to luaFunction { args -> items.decode(args.checkjstring(1)) }))
        api.set("time", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.time())
        })
        api.set("clock", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.clock())
        })
        api.set("player", object : ZeroArgFunction() {
            override fun call(): LuaValue = host.player()?.let(::playerTable) ?: LuaValue.NIL
        })
        api.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = require(arg.checkjstring())
        })
        api.set("export", luaFunction { args -> export(args.arg1()); LuaValue.NONE })
        api.set("import", luaFunction { args -> import(args.checkjstring(1)) })
        api.set("readonly", luaFunction { args -> exports.wrap(args.checktable(1)) })
        api.set("id", id)
        api.set("apiVersion", API_VERSION)
        return api
    }

    private fun createUiApi(): LuaTable {
        val ui = LuaTable()
        ui.set("lerpColor", luaFunction { args ->
            LuaValue.valueOf(LuaColor.unsigned(LuaColor.lerp(LuaColor.of(args.arg(1)), LuaColor.of(args.arg(2)), args.checkdouble(3))))
        })
        ui.set("viewport", luaFunction { _ -> host.viewport()?.let(::viewportTable) ?: LuaValue.NIL })
        if (has(ExtensionPermissions.WIDGETS)) {
            ui.set("widget", gated(ExtensionPermissions.WIDGETS) { args -> defineWidget(args.checkjstring(1), args.checktable(2)); LuaValue.NONE })
            ui.set("tooltip", gated(ExtensionPermissions.WIDGETS) { args -> graphics.tooltip(args.checkjstring(1).take(MAX_TEXT)); LuaValue.NONE })
        }
        if (has(ExtensionPermissions.TOASTS)) {
            ui.set("toast", gated(ExtensionPermissions.TOASTS) { args -> toast(args.checkjstring(1), args.optjstring(2, "")); LuaValue.NONE })
        }
        if (has(ExtensionPermissions.SCREENS)) {
            ui.set("screen", gated(ExtensionPermissions.SCREENS) { args -> defineScreen(args.checkjstring(1), args.checktable(2)); LuaValue.NONE })
            ui.set("open", gated(ExtensionPermissions.SCREENS) { args -> LuaValue.valueOf(openScreen(args.checkjstring(1), args.arg(2))) })
            ui.set("close", gated(ExtensionPermissions.SCREENS) { _ -> LuaValue.valueOf(screenSession?.requestClose() ?: false) })
            ui.set("sound", gated(ExtensionPermissions.SCREENS) { args -> playSound(args.checkjstring(1), args.optdouble(2, 1.0)); LuaValue.NONE })
        }
        return ui
    }

    private fun viewportTable(viewport: ViewportInfo): LuaTable = LuaTable().apply {
        set("width", viewport.width)
        set("height", viewport.height)
        set("scale", viewport.scale)
    }

    private fun playerTable(player: PlayerSnapshot): LuaTable {
        val t = LuaTable()
        t.set("name", player.name)
        t.set("x", player.x)
        t.set("y", player.y)
        t.set("z", player.z)
        if (has(ExtensionPermissions.INVENTORY)) t.set("inventory", gated(ExtensionPermissions.INVENTORY) { _ -> inventory() })
        return t
    }

    /** An API function that needs [permission] from this extension (it exists only then) and from the caller. */
    private fun gated(permission: String, body: (Varargs) -> Varargs): LuaFunction = object : GatedFunction(this) {
        override fun invoke(args: Varargs): Varargs {
            checkCaller(permission, this)
            return body(args)
        }
    }

    // ---- exports / imports --------------------------------------------------------------------------------

    private fun export(value: LuaValue) {
        if (!loading) throw LuaError("kiln.export is only allowed while the extension loads")
        if (!exported.isnil()) throw LuaError("kiln.export may be called only once")
        if (value.isnil()) throw LuaError("kiln.export needs a value")
        exported = value
    }

    private fun import(depId: String): LuaValue {
        if (depId !in dependencies) throw LuaError("'$depId' is not a dependency of '$id' (add it to \"dependencies\" in extension.json)")
        val dependency = host.extension(depId) ?: throw LuaError("dependency '$depId' is not loaded")
        if (!dependency.alive) throw LuaError("dependency '$depId' is unloaded")
        if (dependency.exported.isnil()) throw LuaError("'$depId' exports nothing (kiln.export)")
        return dependency.exportsFor(this).wrap(dependency.exported)
    }

    // ---- screens ------------------------------------------------------------------------------------------

    private fun defineScreen(type: String, def: LuaTable) {
        val name = ownName(type, "screen")
        if (name !in screenDefs && screenDefs.size >= MAX_SCREENS) throw LuaError("too many screens (limit $MAX_SCREENS)")
        for (key in LuaScreenSession.CALLBACKS) requireFunctionOrNil(def.rawget(key), "screen '$type'", key)
        val fullscreen = def.rawget("fullscreen")
        if (!fullscreen.isnil() && !fullscreen.isboolean()) throw LuaError("screen '$type': fullscreen must be a boolean")
        if (!fullscreen.toboolean()) {
            for (key in listOf("width", "height")) {
                val v = def.rawget(key)
                if (!v.isnil() && (!v.isnumber() || v.toint() !in 1..LuaScreenSession.MAX_SIZE)) throw LuaError("screen '$type': $key must be 1..${LuaScreenSession.MAX_SIZE}")
            }
        }
        screenDefs[name] = def
    }

    private fun openScreen(type: String, args: LuaValue): Boolean {
        val name = ownName(type, "screen")
        val def = screenDefs[name] ?: throw LuaError("unknown screen '$type' (define it with kiln.ui.screen)")
        if (loading) throw LuaError("screens cannot be opened while the extension loads")
        if (inCloseCallback) throw LuaError("screens cannot be opened from a close callback")
        if (host.clock() < openBlockedUntil) return false
        if (!openLimit.tryAcquire()) throw LuaError("too many screens opened (limit $MAX_OPENS_PER_10S per 10 s)")
        val session = LuaScreenSession(this, name, def, if (args.isnil()) LuaTable() else args, graphics, host)
        val previous = screenSession
        screenSession = session
        if (!host.openScreen(session)) {
            if (screenSession === session) screenSession = previous?.takeIf { it.state != ScreenState.CLOSED }
            return false
        }
        return true
    }

    /**
     * The player used the guaranteed way out of this extension's screen (Shift+Escape / repeated Escape): it may not
     * open a screen again for [FORCED_OUT_SECONDS], so it cannot trap the player by reopening at once (HUD render,
     * channel handler, ...).
     */
    internal fun forcedOut() {
        openBlockedUntil = host.clock() + FORCED_OUT_SECONDS
    }

    /** The host removed [session] from the screen (Escape, close, replaced). */
    fun screenRemoved(session: LuaScreenSession) {
        inCloseCallback = true
        try {
            session.removed()
        } finally {
            inCloseCallback = false
        }
        if (screenSession === session) screenSession = null
    }

    // ---- sounds -------------------------------------------------------------------------------------------

    /** `sound.play(id [, {volume, pitch}])`: a sound event from the resources of this extension or a dependency. */
    private fun playResourceSound(args: Varargs) {
        val id = ownResource(args.checkjstring(1), "sound")
        val options = args.arg(2)
        val volume = options.optionDouble("volume", 1.0)
        val pitch = options.optionDouble("pitch", 1.0)
        if (!soundLimit.tryAcquire()) return
        host.playSound(id, volume.coerceIn(0.0, 1.0).toFloat(), clampPitch(pitch))
    }

    internal fun playSound(name: String, pitch: Double) {
        if (name !in SOUNDS) throw LuaError("unknown sound '$name' (one of ${SOUNDS.joinToString()})")
        if (!soundLimit.tryAcquire()) return
        host.playSound(name, clampPitch(pitch))
    }

    private fun clampPitch(pitch: Double): Float = pitch.coerceIn(MIN_PITCH, MAX_PITCH).toFloat()

    // ---- other API parts ----------------------------------------------------------------------------------

    private fun inventory(): LuaValue {
        val slots = host.inventory() ?: return LuaValue.NIL
        val result = LuaTable()
        for ((i, slot) in slots.withIndex()) {
            val entry = LuaTable()
            entry.set("slot", slot.slot)
            entry.set("handle", ItemHandle(slot.item))
            entry.set("count", slot.count)
            entry.set("id", slot.item.id)
            entry.set("name", slot.item.name)
            result.set(i + 1, entry)
        }
        return result
    }

    private fun defineWidget(type: String, def: LuaTable) {
        val name = ownName(type, "widget type")
        if (name !in widgetDefs && widgetDefs.size >= MAX_WIDGETS) throw LuaError("too many widget types (limit $MAX_WIDGETS)")
        for (key in WIDGET_CALLBACKS) requireFunctionOrNil(def.rawget(key), "widget '$type'", key)
        widgetDefs[name] = def
    }

    private fun defineHud(layerId: String, options: LuaTable, render: LuaValue) {
        requireValidName(layerId, "HUD layer id")
        if (layerId !in hud && hud.size >= MAX_HUD_LAYERS) throw LuaError("too many HUD layers (limit $MAX_HUD_LAYERS)")
        val anchor = options.get("anchor").optjstring("top_left")
        if (anchor !in ANCHORS) throw LuaError("invalid anchor '$anchor' (one of ${ANCHORS.joinToString()})")
        val width = options.get("width").checkint()
        val height = options.get("height").checkint()
        if (width !in 1..MAX_HUD_SIZE || height !in 1..MAX_HUD_SIZE) throw LuaError("HUD layer size must be 1..$MAX_HUD_SIZE")
        hud[layerId] = HudLayer(layerId, anchor, width, height, render)
    }

    private fun bindKey(keyId: String, options: LuaTable) {
        if (!loading) throw LuaError("keys.bind is only allowed while the extension loads")
        requireValidName(keyId, "keybind id")
        if (keyId in keyHandlers) throw LuaError("keybind '$keyId' is already bound")
        if (keyHandlers.size >= MAX_KEYBINDS) throw LuaError("too many keybinds (limit $MAX_KEYBINDS)")
        val displayName = options.get("name").optjstring(keyId).take(MAX_LABEL)
        val default = defaultKeyName(options.get("default").optjstring(""))
        val press = options.get("press")
        val release = options.get("release")
        requireFunctionOrNil(press, "keybind '$keyId'", "press")
        requireFunctionOrNil(release, "keybind '$keyId'", "release")
        keyHandlers[keyId] = KeyHandlers(press, release)
        host.bindKey(this, keyId, displayName, default)
    }

    /** `kiln.net.channel(name)`: the channel table (created once per name) with `name`, `send` and `on`. */
    private fun channel(channelName: String): LuaValue {
        requireValidName(channelName, "channel name")
        channels[channelName]?.let { return it.table }
        if (channels.size >= MAX_CHANNELS) throw LuaError("too many channels (limit $MAX_CHANNELS)")
        val channel = ExtensionChannel()
        val table = channel.table
        table.set("name", "$id:$channelName")
        table.set("send", gated(ExtensionPermissions.CHANNELS) { args ->
            LuaValue.valueOf(send(channelName, args.withoutSelf(table).arg1()))
        })
        table.set("on", gated(ExtensionPermissions.CHANNELS) { args ->
            val handler = args.withoutSelf(table).arg1().checkfunction()
            if (channel.handlers.size >= MAX_HANDLERS) throw LuaError("too many handlers on channel '$channelName'")
            channel.handlers += handler
            LuaValue.NONE
        })
        channels[channelName] = channel
        return table
    }

    private fun send(channelName: String, message: LuaValue): Boolean {
        val json = LuaJson.encode(message, JSON_LIMITS)
        if (!sendLimit.hasRoom()) return false
        if (!host.send("$id:$channelName", json)) return false
        sendLimit.record()
        return true
    }

    private fun toast(title: String, text: String) {
        if (!toastLimit.tryAcquire()) return
        host.toast(this, title.take(MAX_LABEL), text.take(MAX_TEXT))
    }

    private fun require(file: String): LuaValue {
        val module = file.removeSuffix(".lua")
        if (!MODULE.matches(module)) throw LuaError("invalid module name '$file'")
        modules[module]?.let { return it }
        val source = pkg.file("$module.lua") ?: throw LuaError("module '$module' not found in the package")
        if (!modulesLoading.add(module)) throw LuaError("module '$module' requires itself")
        try {
            val chunk = LuaSandbox.compile(runtime.globals, source, module)
            val result = runtime.call(chunk).arg1()
            val value = if (result.isnil()) LuaValue.TRUE else result
            modules[module] = value
            return value
        } finally {
            modulesLoading.remove(module)
        }
    }

    /** `"<ext>:<name>"` (own namespace) or a bare name -> name. */
    private fun ownName(type: String, what: String): String {
        val ns = type.substringBefore(':', id)
        val name = type.substringAfter(':')
        if (ns != id) throw LuaError("$what '$type' must be in the extension's namespace '$id'")
        if (!Ids.isValidExtensionName(name)) throw LuaError("invalid $what '$type' (name [a-z0-9_]{1,32})")
        return name
    }

    companion object {
        val JSON_LIMITS = JsonLimits(maxDepth = 16, maxEntries = 2_048, maxChars = ProtocolLimits.MAX_EXTENSION_JSON_LENGTH)

        /** Server -> client messages: larger (item lists of a shop page). */
        val INCOMING_JSON_LIMITS = JsonLimits(maxDepth = 16, maxEntries = 32_768, maxChars = ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH)
        const val API_VERSION = 2
        const val MAX_SCREENS = 32
        const val MAX_OPENS_PER_10S = 10

        /** Seconds an extension cannot open screens after the player forced one of them shut. */
        const val FORCED_OUT_SECONDS = 10.0
        const val MAX_SOUNDS_PER_SECOND = 10
        private const val MIN_PITCH = 0.5
        private const val MAX_PITCH = 2.0
        private val RESOURCE_PATH = Regex("[a-z0-9_./-]{1,128}")

        /** `kiln.ui.sound` / `s:sound` names (mapped to vanilla sound events by the runtime). */
        val SOUNDS = listOf("click", "pling", "success", "error", "page", "toast")
        val ANCHORS = listOf("top_left", "top", "top_right", "left", "center", "right", "bottom_left", "bottom", "bottom_right")
        private val WIDGET_CALLBACKS = listOf("init", "render", "click", "press", "release", "scroll")
        const val MAX_WIDGETS = 64
        const val MAX_HUD_LAYERS = 16
        const val MAX_HUD_SIZE = 512
        const val MAX_KEYBINDS = 32
        const val MAX_CHANNELS = 32
        const val MAX_HANDLERS = 8
        const val MAX_SENDS_PER_SECOND = 40
        const val MAX_TOASTS_PER_10S = 5
        const val MAX_TEXT = 512

        /** Length of short labels: toast titles, keybind names. */
        private const val MAX_LABEL = 64
        private val MODULE = Regex("[a-z0-9_]+(/[a-z0-9_]+)*")

        /** `"J"` -> `key.keyboard.j`, `"key.mouse.middle"` stays, `"left.shift"` -> `key.keyboard.left.shift`. */
        fun defaultKeyName(spec: String): String {
            val s = spec.trim().lowercase()
            return when {
                s.isEmpty() -> "key.keyboard.unknown"
                s.startsWith("key.") -> s
                else -> "key.keyboard.$s"
            }
        }
    }
}
