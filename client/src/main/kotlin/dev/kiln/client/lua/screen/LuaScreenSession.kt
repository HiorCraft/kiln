package dev.kiln.client.lua.screen

import dev.kiln.client.lua.ExtensionHost
import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.graphics.LuaGraphics
import dev.kiln.client.lua.luaFunction
import dev.kiln.client.lua.withoutSelf
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import kotlin.math.floor

/**
 * State and Lua side of one open Lua screen (`kiln.ui.open`), independent of Minecraft (the runtime's
 * `LuaScreen` forwards input and rendering; tests drive it directly).
 *
 * Callbacks of the definition (all optional, all get the per-screen state `self` and the screen object `s`):
 * `init(self, s, args)`, `render(self, g, s)`, `resize(self, s)`, `close(self, s, reason)`,
 * `key(self, s, key, mods) -> bool`, `char(self, s, ch) -> bool`, `click(self, s, mx, my, button, mods) -> bool`,
 * `release(self, s, mx, my, button)`, `drag(self, s, mx, my, button, dx, dy)`, `scroll(self, s, mx, my, dy, dx) -> bool`.
 * Mouse coordinates are relative to the screen origin (`s.x`, `s.y`), buttons 0/1/2 = left/right/middle.
 *
 * Lifecycle: [layout] (first time: `init`, afterwards `resize`) -> frames ([render]) and input -> [removed] (once:
 * `close` with the reason `"escape"`, `"closed"` (ui.close / s:close) or `"replaced"` (another screen opened)) or
 * [dispose] (disconnect / extension unloaded: no Lua runs any more).
 */
class LuaScreenSession internal constructor(
    val extension: LuaExtension,
    /** Screen name inside the extension's namespace. */
    val name: String,
    private val def: LuaTable,
    private val args: LuaValue,
    internal val graphics: LuaGraphics,
    private val host: ExtensionHost,
) {
    var state: ScreenState = ScreenState.NEW
        private set

    val self = LuaTable()
    val screen = LuaTable()

    val fullscreen: Boolean = def.rawget("fullscreen").toboolean()
    private val defWidth: Int = if (fullscreen) 0 else def.rawget("width").optint(DEFAULT_WIDTH)
    private val defHeight: Int = if (fullscreen) 0 else def.rawget("height").optint(DEFAULT_HEIGHT)

    /** Absolute origin and size of the screen area (fullscreen: the whole window). */
    var x = 0; private set
    var y = 0; private set
    var width = 0; private set
    var height = 0; private set

    var view: ScreenView? = null

    private var closeReason: String? = null
    private var openedAt = 0.0
    private var lastFrame = -1.0

    /** Number of the frame being (or last) rendered; text inputs not declared in it are gone. */
    internal var frame = 0
        private set
    private var rendering = false
    private val pressed = HashSet<Int>()
    private var mods = ModifierKeys(false, false, false)
    private var mouseAbsX = 0
    private var mouseAbsY = 0

    /** Clock times of recent Escape presses (see [forcedEscape]). */
    private val escapeTimes = ArrayDeque<Double>()

    private val inputs = LinkedHashMap<String, TextInput>()

    /** Text input with keyboard focus (gets keys and characters before Lua). */
    var focused: TextInput? = null
        private set

    init {
        if (!fullscreen && (defWidth !in 1..MAX_SIZE || defHeight !in 1..MAX_SIZE)) throw LuaError("screen size must be 1..$MAX_SIZE")
        screen.rawset("id", LuaValue.valueOf("${extension.id}:$name"))
        screen.rawset("fullscreen", LuaValue.valueOf(fullscreen))
        method("textInput") { a -> textInput(a.checkjstring(1), a.opttable(2, LuaTable())) }
        method("cursor") { a ->
            if (!rendering) throw LuaError("s:cursor can only be used while rendering")
            val name = a.checkjstring(1)
            if (name !in CURSORS) throw LuaError("unknown cursor '$name' (one of ${CURSORS.joinToString()})")
            graphics.currentTarget().cursor(name)
            LuaValue.NONE
        }
        method("close") { _ -> LuaValue.valueOf(requestClose()) }
        method("sound") { a -> extension.playSound(a.checkjstring(1), a.optdouble(2, 1.0)); LuaValue.NONE }
        method("isMouseDown") { a -> LuaValue.valueOf(a.optint(1, 0) in pressed) }
        method("focused") { _ -> focused?.let { LuaValue.valueOf(it.id) } ?: LuaValue.NIL }
        method("blur") { _ -> setFocus(null); LuaValue.NONE }
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    /** Window (re)sized to [viewportWidth] x [viewportHeight] GUI pixels: places the screen, runs `init` / `resize`. */
    fun layout(viewportWidth: Int, viewportHeight: Int, guiScale: Double) {
        if (state == ScreenState.CLOSED) return
        if (fullscreen) {
            x = 0; y = 0; width = viewportWidth; height = viewportHeight
        } else {
            width = defWidth; height = defHeight
            x = (viewportWidth - width) / 2; y = (viewportHeight - height) / 2
        }
        screen.rawset("x", x); screen.rawset("y", y)
        screen.rawset("width", width); screen.rawset("height", height)
        screen.rawset("viewportWidth", viewportWidth); screen.rawset("viewportHeight", viewportHeight)
        screen.rawset("guiScale", guiScale)
        if (state == ScreenState.NEW) {
            state = ScreenState.OPEN
            openedAt = host.clock()
            screen.rawset("time", 0.0); screen.rawset("delta", 0.0)
            call("init", self, screen, args)
        } else {
            call("resize", self, screen)
        }
    }

    /** Draws one frame; [mouseX] / [mouseY] absolute. */
    fun render(target: DrawTarget, mouseX: Int, mouseY: Int) {
        if (state != ScreenState.OPEN || !extension.alive) return
        val now = host.clock()
        screen.rawset("time", now - openedAt)
        screen.rawset("delta", if (lastFrame < 0) 0.0 else now - lastFrame)
        lastFrame = now
        setMouse(mouseX.toDouble(), mouseY.toDouble())
        frame++
        val render = def.rawget("render")
        if (render.isfunction()) {
            rendering = true
            try {
                graphics.draw(target, x, y, allowTooltip = true) {
                    extension.invokeFrame(render, LuaValue.varargsOf(self, graphics.table, screen))
                }
            } finally {
                rendering = false
            }
        }
        // inputs not declared in this frame disappear (and lose focus)
        val gone = inputs.values.filter { !it.declaredIn(frame) }
        for (input in gone) {
            inputs.remove(input.id)
            if (focused === input) setFocus(null)
        }
    }

    /** The screen went away (Escape, ui.close, replaced). Runs `close` once. */
    fun removed() {
        if (state != ScreenState.OPEN) {
            state = ScreenState.CLOSED
            return
        }
        dispose()
        call("close", self, screen, LuaValue.valueOf(closeReason ?: "replaced"))
    }

    /** Disconnect / unload: closed without running Lua. */
    fun dispose() {
        state = ScreenState.CLOSED
        inputs.clear()
        focused = null
    }

    /** `ui.close()` / `s:close()`: asks the host to close this screen; `close` runs with reason `"closed"`. */
    fun requestClose(): Boolean {
        if (state != ScreenState.OPEN) return false
        closeReason = "closed"
        host.closeScreen(this)
        return true
    }

    /** The player pressed Escape and Lua did not handle it. */
    fun closingByEscape() {
        closeReason = "escape"
    }

    /** Records an Escape press; `true` if it must close the screen regardless of Lua (see [keyPressed]). */
    private fun forcedEscape(mods: ModifierKeys): Boolean {
        if (mods.shift) return true
        val now = host.clock()
        while (escapeTimes.isNotEmpty() && now - escapeTimes.first() > FORCED_ESCAPE_SECONDS) escapeTimes.removeFirst()
        escapeTimes.addLast(now)
        return escapeTimes.size >= FORCED_ESCAPE_PRESSES
    }

    // ---- input --------------------------------------------------------------------------------------------

    /**
     * Key press not consumed by the focused text input. [key] is the short key name (`escape`, `enter`, `tab`,
     * `a`, `left.shift`, `keypad.enter`, ...).
     *
     * **Guaranteed way out** (the script cannot block it): Shift+Escape, or the [FORCED_ESCAPE_PRESSES]th Escape
     * within [FORCED_ESCAPE_SECONDS], closes the screen without asking Lua's `key` (`close` still runs with reason
     * `"escape"`). Otherwise an extension could keep the player in its screen by returning `true` for every Escape.
     */
    fun keyPressed(key: String, mods: ModifierKeys): KeyResult {
        if (state != ScreenState.OPEN) return KeyResult.PASS
        setMods(mods)
        if (key == "escape" && forcedEscape(mods)) {
            extension.forcedOut()
            closingByEscape()
            return KeyResult.CLOSE
        }
        if (callBool("key", self, screen, LuaValue.valueOf(key), modsTable(mods))) return KeyResult.HANDLED
        if (state != ScreenState.OPEN) return KeyResult.HANDLED
        if (key == "tab") {
            cycleFocus(backwards = mods.shift)
            return KeyResult.HANDLED
        }
        if (key == "escape") {
            closingByEscape()
            return KeyResult.CLOSE
        }
        return KeyResult.PASS
    }

    /** Character typed while no text input has focus. */
    fun charTyped(ch: String): Boolean {
        if (state != ScreenState.OPEN) return false
        return callBool("char", self, screen, LuaValue.valueOf(ch))
    }

    /** Mouse press at absolute ([mx], [my]); text inputs first (topmost = declared last), then Lua `click`. */
    fun mouseClicked(mx: Double, my: Double, button: Int, mods: ModifierKeys, doubleClick: Boolean = false): Boolean {
        if (state != ScreenState.OPEN) return false
        setMods(mods)
        setMouse(mx, my)
        if (button >= 0) pressed += button
        val hit = inputs.values.lastOrNull { it.hit(mx, my) }
        if (hit != null) {
            setFocus(hit)
            hit.box.mouseClicked(mx, my, button, doubleClick)
            return true
        }
        setFocus(null)
        return callBool("click", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button), modsTable(mods))
    }

    fun mouseReleased(mx: Double, my: Double, button: Int) {
        if (state != ScreenState.OPEN) return
        pressed -= button
        setMouse(mx, my)
        call("release", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button))
    }

    fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double) {
        if (state != ScreenState.OPEN) return
        setMouse(mx, my)
        call("drag", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button), LuaValue.valueOf(dx), LuaValue.valueOf(dy))
    }

    fun mouseScrolled(mx: Double, my: Double, dx: Double, dy: Double): Boolean {
        if (state != ScreenState.OPEN) return false
        setMouse(mx, my)
        return callBool("scroll", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(dy), LuaValue.valueOf(dx))
    }

    // ---- text inputs --------------------------------------------------------------------------------------

    private fun textInput(id: String, opts: LuaTable): LuaValue {
        if (!rendering) throw LuaError("s:textInput can only be used while rendering")
        if (!INPUT_ID.matches(id)) throw LuaError("invalid text input id '$id' ([A-Za-z0-9_.:-]{1,64})")
        val view = view ?: throw LuaError("screen is not shown")
        var input = inputs[id]
        val fresh = input == null
        if (input == null) {
            if (inputs.size >= MAX_INPUTS) throw LuaError("too many text inputs (limit $MAX_INPUTS)")
            var created: TextInput? = null
            val box = view.createTextBox { value -> created?.edited(value) }
            input = TextInput(this, id, box)
            created = input
            inputs[id] = input
        } else if (input.declaredIn(frame)) {
            throw LuaError("text input '$id' declared twice in one frame")
        }
        input.declare(opts, fresh, graphics.currentTarget(), mouseAbsX, mouseAbsY)
        return input.field
    }

    /** Text inputs declared in the last frame, in declaration (= Tab) order. */
    val textInputs: Collection<TextInput> get() = inputs.values

    fun setFocus(input: TextInput?) {
        val old = focused
        if (old === input) return
        focused = input
        old?.box?.focused = false
        input?.box?.focused = true
        old?.focusChanged()
        input?.focusChanged()
    }

    /** Tab / Shift+Tab: next enabled input in declaration order (wrapping). */
    fun cycleFocus(backwards: Boolean) {
        val candidates = inputs.values.filter { it.enabled && it.declaredIn(frame) }
        if (candidates.isEmpty()) return
        val index = candidates.indexOf(focused)
        val next = when {
            index < 0 -> if (backwards) candidates.last() else candidates.first()
            backwards -> candidates[(index - 1 + candidates.size) % candidates.size]
            else -> candidates[(index + 1) % candidates.size]
        }
        setFocus(next)
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun setMouse(mx: Double, my: Double) {
        screen.rawset("mouseX", mx - x)
        screen.rawset("mouseY", my - y)
        mouseAbsX = floor(mx).toInt()
        mouseAbsY = floor(my).toInt()
    }

    private fun setMods(m: ModifierKeys) {
        mods = m
        writeMods(screen, m)
    }

    private fun modsTable(m: ModifierKeys): LuaTable = LuaTable().also { writeMods(it, m) }

    private fun writeMods(table: LuaTable, m: ModifierKeys) {
        table.rawset("shift", LuaValue.valueOf(m.shift))
        table.rawset("ctrl", LuaValue.valueOf(m.ctrl))
        table.rawset("alt", LuaValue.valueOf(m.alt))
    }

    private fun call(name: String, vararg args: LuaValue) {
        val fn = def.rawget(name)
        if (fn.isfunction()) extension.invoke(fn, LuaValue.varargsOf(args))
    }

    private fun callBool(name: String, vararg args: LuaValue): Boolean {
        val fn = def.rawget(name)
        if (!fn.isfunction()) return false
        return extension.invoke(fn, LuaValue.varargsOf(args))?.arg1()?.toboolean() ?: true
    }

    /** Registers `s:<name>(...)` (also callable as `s.<name>(...)`). */
    private fun method(name: String, body: (Varargs) -> Varargs) {
        screen.rawset(name, luaFunction { args -> body(args.withoutSelf(screen)) })
    }

    companion object {
        const val DEFAULT_WIDTH = 176
        const val DEFAULT_HEIGHT = 166
        const val MAX_SIZE = 4_096
        const val MAX_INPUTS = 64
        const val DEFAULT_INPUT_HEIGHT = 20
        const val DEFAULT_MAX_LENGTH = 256

        /** Escape presses within [FORCED_ESCAPE_SECONDS] that always close a Lua screen (Lua may swallow fewer). */
        const val FORCED_ESCAPE_PRESSES = 3
        const val FORCED_ESCAPE_SECONDS = 1.5
        val CALLBACKS = listOf("init", "render", "resize", "close", "key", "char", "click", "release", "drag", "scroll")
        val CURSORS = setOf("default", "hand", "text", "crosshair", "resize_ns", "resize_ew", "move", "not_allowed")
        private val INPUT_ID = Regex("[A-Za-z0-9_.:-]{1,64}")

        /** `key.keyboard.left.shift` -> `left.shift`, `key.mouse.left` -> `mouse.left`. */
        fun shortKeyName(name: String): String = when {
            name.startsWith("key.keyboard.") -> name.removePrefix("key.keyboard.")
            name.startsWith("key.") -> name.removePrefix("key.")
            else -> name
        }
    }
}
