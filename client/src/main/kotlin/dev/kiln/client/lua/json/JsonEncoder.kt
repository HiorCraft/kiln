package dev.kiln.client.lua.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.IdentityHashMap

/** Largest integer a double represents exactly (2^53): integral numbers up to it are written without fraction. */
private const val MAX_EXACT_INTEGER = 9.007199254740992E15

/** One Lua -> JSON conversion: counts entries over the whole message and tracks the tables on the path (cycles). */
internal class JsonEncoder(private val limits: JsonLimits) {
    private var entryCount = 0
    private val path = IdentityHashMap<LuaTable, Unit>()

    fun message(table: LuaTable): JsonElement = toJson(table, depth = 1, topLevel = true)

    private fun toJson(table: LuaTable, depth: Int, topLevel: Boolean): JsonElement {
        if (depth > limits.maxDepth) throw LuaError("message nested too deeply (limit ${limits.maxDepth})")
        if (path.put(table, Unit) != null) throw LuaError("message contains a cycle")
        try {
            val entries = entriesOf(table)
            if (entries.isEmpty()) return if (topLevel) JsonObject() else JsonArray()

            val allStrings = entries.all { it.first.type() == LuaValue.TSTRING }
            if (!topLevel && !allStrings && isSequence(entries)) return array(entries, depth)
            if (!allStrings) {
                throw LuaError(if (topLevel) "message must have string keys only" else "table mixes array and object keys (or has non-string keys)")
            }
            val obj = JsonObject()
            for ((k, v) in entries) obj.add(k.tojstring(), valueToJson(v, depth))
            return obj
        } finally {
            path.remove(table)
        }
    }

    private fun entriesOf(table: LuaTable): List<Pair<LuaValue, LuaValue>> {
        val result = ArrayList<Pair<LuaValue, LuaValue>>()
        var key: LuaValue = LuaValue.NIL
        while (true) {
            val next = table.next(key)
            key = next.arg1()
            if (key.isnil()) break
            if (++entryCount > limits.maxEntries) throw LuaError("message has too many entries (limit ${limits.maxEntries})")
            result += key to next.arg(2)
        }
        return result
    }

    private fun array(entries: List<Pair<LuaValue, LuaValue>>, depth: Int): JsonArray {
        val array = JsonArray()
        val byIndex = entries.associate { it.first.toint() to it.second }
        for (i in 1..entries.size) array.add(valueToJson(byIndex.getValue(i), depth))
        return array
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

    private fun valueToJson(value: LuaValue, depth: Int): JsonElement =
        when (value.type()) {
            LuaValue.TBOOLEAN -> JsonPrimitive(value.toboolean())
            LuaValue.TSTRING -> JsonPrimitive(value.tojstring())
            LuaValue.TNUMBER -> {
                val d = value.todouble()
                if (d.isNaN() || d.isInfinite()) throw LuaError("message contains NaN or infinity")
                if (d == Math.floor(d) && Math.abs(d) <= MAX_EXACT_INTEGER) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            LuaValue.TTABLE -> toJson(value as LuaTable, depth + 1, topLevel = false)
            else -> throw LuaError("message cannot contain a ${value.typename()}")
        }
}
