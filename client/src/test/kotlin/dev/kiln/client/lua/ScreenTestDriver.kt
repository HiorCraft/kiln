package dev.kiln.client.lua

import dev.kiln.client.lua.screen.LuaScreenSession
import dev.kiln.client.lua.screen.ModifierKeys
import org.luaj.vm2.LuaValue

/** No modifier key held. */
val NO_MODS = ModifierKeys(false, false, false)

/** The global Lua value at dotted [path] (`"a.b.c"`) of [ext]. */
fun lua(ext: LuaExtension, path: String): LuaValue = path.split('.').fold(ext.runtime.globals as LuaValue) { t, k -> t.get(k) }

/** Renders one frame of [session] with the mouse at ([mx], [my]); returns what was drawn. */
fun frame(session: LuaScreenSession, mx: Int = 0, my: Int = 0, target: CountingTarget = CountingTarget()): CountingTarget {
    session.render(target, mx, my)
    return target
}

/** Clicks like a player: the frame before knows the regions, the frame after reports the click. */
fun click(session: LuaScreenSession, x: Int, y: Int, mods: ModifierKeys = NO_MODS): Boolean {
    frame(session, x, y)
    val used = session.mouseClicked(x.toDouble(), y.toDouble(), 0, mods)
    session.mouseReleased(x.toDouble(), y.toDouble(), 0)
    frame(session, x, y)
    return used
}
