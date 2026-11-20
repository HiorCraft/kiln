package dev.kiln.client.lua.json

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.io.StringReader

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
    fun encode(value: LuaValue, limits: JsonLimits = JsonLimits()): String {
        val table = value as? LuaTable ?: throw LuaError("message must be a table, got ${value.typename()}")
        val text = JsonEncoder(limits).message(table).toString()
        if (text.length > limits.maxChars) throw LuaError("message too large (limit ${limits.maxChars} characters)")
        return text
    }

    fun decode(json: String, limits: JsonLimits = JsonLimits()): LuaValue {
        if (json.length > limits.maxChars) throw LuaError("message too large")
        return JsonDecoder(limits).toLua(parse(json), depth = 1)
    }

    private fun parse(json: String): JsonElement = try {
        val reader = JsonReader(StringReader(json))
        val parsed = JsonParser.parseReader(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw LuaError("trailing data after JSON value")
        parsed
    } catch (e: LuaError) {
        throw e
    } catch (e: Exception) {
        throw LuaError("invalid JSON: ${e.message}")
    }
}
