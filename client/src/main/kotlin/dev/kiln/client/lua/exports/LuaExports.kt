package dev.kiln.client.lua.exports

import dev.kiln.client.lua.LuaExtension
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * Read-only views of one extension's values for other extensions (`kiln.export` / `kiln.import`,
 * `kiln.readonly`).
 *
 * - Tables become [ReadOnlyProxy]s: reads go to the real table (through its metatable, so "classes" work) and wrap
 *   the result again (recursively, also keys and values seen by `pairs`/`ipairs`/`next`), every write - `t.k = v`,
 *   `table.insert/remove/sort`, `setmetatable` - is an error, `getmetatable` returns the string `"read-only"`.
 * - Functions become [ExportedFunction]s: they refuse to run once the exporting extension is unloaded, and hand
 *   proxies of the exporter's own tables back as the real tables (so the exporter can update state objects it gave
 *   out). Plain tables and Lua functions in arguments and return values are passed unchanged (a function of an API
 *   extension returns what it wants to give out: fresh tables, or `kiln.readonly(t)` for shared ones); **views and
 *   permission-gated API functions** in arguments and return values get the holder of the call ([adopt]).
 * - Strings, numbers, booleans, nil and item handles are immutable and passed as they are.
 *
 * **Holder** (see `docs/lua-extensions.md`, "Kein Ausleihen von Rechten"): every view knows its holder, the importer
 * it was made for, or `null` = unknown (`kiln.readonly`, which can end up anywhere). While an [ExportedFunction] runs,
 * its holder must have every permission a gated function inside needs; an unknown holder has none ("deny by
 * default"), so it does not matter by which path a holderless view travels. A view of another view is made for the
 * new holder ([wrap]): a holderless one of the same exporter is re-made for this holder, any other is nested.
 *
 * **Invariant: a holder that is on a view is never lost.** Every way a view can travel - proxy reads, `pairs`,
 * arguments and return values of exported functions ([adopt]), plain tables, callbacks - either passes the view
 * object itself on, or wraps it into another view, which keeps the inner one. Only the unknown holder (`null`) is
 * replaced. The one exception is a table proxy given back to its own exporter ([unwrap]): it becomes the real table
 * again (the exporter may change its state objects); a table holds no rights, but raw gated functions the exporter
 * itself stored in it are then raw again (documented limit, see `docs/lua-extensions.md`).
 *
 * Wrappers are cached per value so `ui.button == ui.button` holds; the caches are bounded ([MAX_CACHED]) and cleared
 * when full (identities of wrappers can then change, values never do).
 */
class LuaExports(
    internal val owner: LuaExtension,
    /**
     * The importer these views were made for (`kiln.import`), `null` for `kiln.readonly` views. Every exported
     * function remembers it: whoever ends up calling it - the holder itself, or the exporter's own code after the
     * holder handed the function back (as callback, metamethod, ...) - needs the holder's permissions as well.
     */
    internal val holder: LuaExtension? = null,
) {
    private val tables = HashMap<LuaValue, ReadOnlyProxy>()
    private val functions = HashMap<LuaValue, ExportedFunction>()

    fun wrap(value: LuaValue): LuaValue = when {
        value is ReadOnlyProxy && value.exports === this -> value
        value is ExportedFunction && value.exports === this -> value
        // a view of this exporter without holder (kiln.readonly): re-made for this holder, nothing is lost
        value is ReadOnlyProxy && value.exports.owner === owner && value.exports.holder == null -> wrap(value.target)
        value is ExportedFunction && value.exports.owner === owner && value.exports.holder == null -> wrap(value.target)
        // tables and functions, also views with another holder (of this or another exporter): nested, so every
        // holder stays (never re-made: that would drop the earlier holder, "holder laundering")
        value is LuaTable -> tables[value] ?: ReadOnlyProxy(value, this).also { cache(tables, value, it) }
        value.isfunction() -> functions[value] ?: ExportedFunction(value, this).also { cache(functions, value, it) }
        else -> value
    }

    /**
     * A value crossing the boundary of a call of an [ExportedFunction] (argument or return value): views and gated API
     * functions get this view's holder, so a raw gated function or a holderless view is not passed on without one.
     * Plain tables and Lua functions stay as they are (their code is checked as the direct caller anyway).
     */
    internal fun adopt(value: LuaValue): LuaValue =
        if (value is ReadOnlyProxy || value is ExportedFunction || value is GatedFunction) wrap(value) else value

    /** The real table behind a proxy (also nested, any holders) made by this exporter (anything else unchanged). */
    fun unwrap(value: LuaValue): LuaValue {
        var v = value
        var result = value
        while (v is ReadOnlyProxy) {
            val layer = v
            v = v.target
            if (layer.exports.owner === owner) result = v
        }
        return result
    }

    private fun <V> cache(map: HashMap<LuaValue, V>, key: LuaValue, value: V) {
        if (map.size >= MAX_CACHED) map.clear()
        map[key] = value
    }

    companion object {
        const val MAX_CACHED = 4_096
        const val LOCK = "read-only"
    }
}
