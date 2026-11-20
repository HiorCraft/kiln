package dev.kiln.client.lua

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.io.StringReader
import java.util.IdentityHashMap

/**
 * Lua table <-> JSON for channel messages.
 *
 * Lua -> JSON:
 * - The message itself must be a table and becomes a JSON **object** (an empty table becomes `{}`).
 * - Nested tables: keys `1..n` without gaps (n >= 1) -> array; only string keys -> object; an empty nested table ->
 *   `[]`. Mixed or other keys (floats, booleans, tables) are an error.
 * - Values: string, boolean, number (integral values without fraction, `NaN`/`inf` are an error), table.
 *   Functions/userdata are an error; `nil` cannot be stored in a table anyway.
 * - Limits: nesting depth, number of entries in total, encoded length (characters); cycles are an error.
 *
 * JSON -> Lua: objects and arrays -> tables (arrays 1-based), numbers -> Lua numbers, `null` -> `nil` (the key is
 * absent). The same depth/entry limits apply.
 */
object LuaJson {
    data class Limits(val maxDepth: Int = 16, val maxEntries: Int = 2_048, val maxChars: Int = 16_384)

    fun encode(value: LuaValue, limits: Limits = Limits()): String {
        val table = value as? LuaTable ?: throw LuaError("message must be a table, got ${value.typename()}")
        val counter = IntArray(1)
        val json = toJson(table, limits, 1, counter, IdentityHashMap(), topLevel = true)
        val text = json.toString()
        if (text.length > limits.maxChars) throw LuaError("message too large (limit ${limits.maxChars} characters)")
        return text
    }

    fun decode(json: String, limits: Limits = Limits()): LuaValue {
        if (json.length > limits.maxChars) throw LuaError("message too large")
        val element = try {
            val reader = JsonReader(StringReader(json))
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw LuaError("trailing data after JSON value")
            parsed
        } catch (e: LuaError) {
            throw e
        } catch (e: Exception) {
            throw LuaError("invalid JSON: ${e.message}")
        }
        return toLua(element, limits, 1, IntArray(1))
    }

    private fun toJson(table: LuaTable, limits: Limits, depth: Int, counter: IntArray, path: IdentityHashMap<LuaTable, Unit>, topLevel: Boolean): JsonElement {
        if (depth > limits.maxDepth) throw LuaError("message nested too deeply (limit ${limits.maxDepth})")
        if (path.put(table, Unit) != null) throw LuaError("message contains a cycle")
        try {
            val entries = ArrayList<Pair<LuaValue, LuaValue>>()
            var key: LuaValue = LuaValue.NIL
            while (true) {
                val next = table.next(key)
                key = next.arg1()
                if (key.isnil()) break
                if (++counter[0] > limits.maxEntries) throw LuaError("message has too many entries (limit ${limits.maxEntries})")
                entries += key to next.arg(2)
            }
            if (entries.isEmpty()) return if (topLevel) JsonObject() else JsonArray()

            val allStrings = entries.all { it.first.type() == LuaValue.TSTRING }
            if (!topLevel && !allStrings && isSequence(entries)) {
                val array = JsonArray()
                val byIndex = entries.associate { it.first.toint() to it.second }
                for (i in 1..entries.size) array.add(valueToJson(byIndex.getValue(i), limits, depth, counter, path))
                return array
            }
            if (!allStrings) {
                throw LuaError(if (topLevel) "message must have string keys only" else "table mixes array and object keys (or has non-string keys)")
            }
            val obj = JsonObject()
            for ((k, v) in entries) obj.add(k.tojstring(), valueToJson(v, limits, depth, counter, path))
            return obj
        } finally {
            path.remove(table)
        }
    }

    private fun isSequence(entries: List<Pair<LuaValue, LuaValue>>): Boolean {
        val n = entries.size
        val seen = BooleanArray(n + 1)
        for ((k, _) in entries) {
            if (!k.isinttype() && !(k.isnumber() && k.todouble() == Math.floor(k.todouble()))) return false
            val d = k.todouble()
            if (d < 1 || d > n) return false
            seen[d.toInt()] = true
        }
        return (1..n).all { seen[it] }
    }

    private fun valueToJson(value: LuaValue, limits: Limits, depth: Int, counter: IntArray, path: IdentityHashMap<LuaTable, Unit>): JsonElement =
        when (value.type()) {
            LuaValue.TBOOLEAN -> JsonPrimitive(value.toboolean())
            LuaValue.TSTRING -> JsonPrimitive(value.tojstring())
            LuaValue.TNUMBER -> {
                val d = value.todouble()
                if (d.isNaN() || d.isInfinite()) throw LuaError("message contains NaN or infinity")
                if (d == Math.floor(d) && Math.abs(d) <= 9.007199254740992E15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            LuaValue.TTABLE -> toJson(value as LuaTable, limits, depth + 1, counter, path, topLevel = false)
            else -> throw LuaError("message cannot contain a ${value.typename()}")
        }

    private fun toLua(element: JsonElement, limits: Limits, depth: Int, counter: IntArray): LuaValue {
        if (depth > limits.maxDepth + 1) throw LuaError("message nested too deeply")
        if (++counter[0] > limits.maxEntries + 1) throw LuaError("message has too many entries")
        return when (element) {
            is JsonNull -> LuaValue.NIL
            is JsonPrimitive -> when {
                element.isBoolean -> LuaValue.valueOf(element.asBoolean)
                element.isNumber -> {
                    val d = element.asDouble
                    if (d == Math.floor(d) && Math.abs(d) < Int.MAX_VALUE) LuaValue.valueOf(d.toInt()) else LuaValue.valueOf(d)
                }
                else -> LuaValue.valueOf(element.asString)
            }
            is JsonArray -> {
                val table = LuaTable()
                element.forEachIndexed { i, e -> table.set(i + 1, toLua(e, limits, depth + 1, counter)) }
                table
            }
            is JsonObject -> {
                val table = LuaTable()
                for ((k, e) in element.entrySet()) table.set(k, toLua(e, limits, depth + 1, counter))
                table
            }
            else -> LuaValue.NIL
        }
    }
}
