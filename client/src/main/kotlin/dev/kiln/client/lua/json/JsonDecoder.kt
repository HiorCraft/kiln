package dev.kiln.client.lua.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/** One JSON -> Lua conversion: counts elements over the whole message. */
internal class JsonDecoder(private val limits: JsonLimits) {
    private var elements = 0

    fun toLua(element: JsonElement, depth: Int): LuaValue {
        if (depth > limits.maxDepth + 1) throw LuaError("message nested too deeply")
        if (++elements > limits.maxEntries + 1) throw LuaError("message has too many entries")
        return when (element) {
            is JsonNull -> LuaValue.NIL
            is JsonPrimitive -> primitive(element)
            is JsonArray -> {
                val table = LuaTable()
                element.forEachIndexed { i, e -> table.set(i + 1, toLua(e, depth + 1)) }
                table
            }
            is JsonObject -> {
                val table = LuaTable()
                for ((k, e) in element.entrySet()) table.set(k, toLua(e, depth + 1))
                table
            }
            else -> LuaValue.NIL
        }
    }

    private fun primitive(element: JsonPrimitive): LuaValue = when {
        element.isBoolean -> LuaValue.valueOf(element.asBoolean)
        element.isNumber -> {
            val d = element.asDouble
            if (d == Math.floor(d) && Math.abs(d) < Int.MAX_VALUE) LuaValue.valueOf(d.toInt()) else LuaValue.valueOf(d)
        }
        else -> LuaValue.valueOf(element.asString)
    }
}
