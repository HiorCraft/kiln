package dev.kiln.client.lua

import dev.kiln.protocol.Ids
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** A Lua function that hands all its arguments to [body]. */
internal fun luaFunction(body: (Varargs) -> Varargs): VarArgFunction = object : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs = body(args)
}

/** A table with the given string keys. */
internal fun luaTable(vararg entries: Pair<String, LuaValue>): LuaTable = LuaTable().apply {
    for ((key, value) in entries) set(key, value)
}

/** Arguments of a function callable as method (`t:f(x)`) and plainly (`t.f(x)`): without [self] if it came first. */
internal fun Varargs.withoutSelf(self: LuaValue): Varargs = if (arg1() === self) subargs(2) else this

/** Number [key] of an options table, [default] if this is no table or the key is absent. */
internal fun LuaValue.optionDouble(key: String, default: Double): Double = if (istable()) get(key).optdouble(default) else default

/** String [key] of an options table, [default] if this is no table or the key is absent. */
internal fun LuaValue.optionString(key: String, default: String): String = if (istable()) get(key).optjstring(default) else default

/** Boolean [key] of an options table, [default] if this is no table or the key is absent. */
internal fun LuaValue.optionBoolean(key: String, default: Boolean): Boolean {
    if (!istable()) return default
    val value = get(key)
    return if (value.isnil()) default else value.toboolean()
}

/** `"<context>: <key> must be a function"` unless [value] is `nil` or a function. */
internal fun requireFunctionOrNil(value: LuaValue, context: String, key: String) {
    if (!value.isnil() && !value.isfunction()) throw LuaError("$context: $key must be a function")
}

/** `"invalid <what> '<name>' ([a-z0-9_]{1,32})"` unless [name] is a valid extension-local name. */
internal fun requireValidName(name: String, what: String) {
    if (!Ids.isValidExtensionName(name)) throw LuaError("invalid $what '$name' ([a-z0-9_]{1,32})")
}
