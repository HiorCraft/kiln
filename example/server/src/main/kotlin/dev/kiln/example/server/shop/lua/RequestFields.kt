package dev.kiln.example.server.shop.lua

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Strict accessors for untrusted request fields: `null` when missing, of the wrong type or out of range. */
object RequestFields {
    private fun primitive(obj: JsonObject?, name: String): JsonPrimitive? {
        val e: JsonElement = obj?.get(name) ?: return null
        return if (e.isJsonPrimitive) e.asJsonPrimitive else null
    }

    /** An integral JSON number in [min]..[max] (no fraction, no string). */
    fun long(obj: JsonObject?, name: String, min: Long, max: Long): Long? {
        val p = primitive(obj, name) ?: return null
        if (!p.isNumber) return null
        val d = p.asDouble
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || d < min.toDouble() || d > max.toDouble()) return null
        val value = p.asBigDecimal
        return runCatching { value.longValueExact() }.getOrNull()?.takeIf { it in min..max }
    }

    fun int(obj: JsonObject?, name: String, min: Int, max: Int): Int? = long(obj, name, min.toLong(), max.toLong())?.toInt()

    /** A JSON string of at most [maxLength] characters without control characters. */
    fun string(obj: JsonObject?, name: String, maxLength: Int): String? {
        val p = primitive(obj, name) ?: return null
        if (!p.isString) return null
        val s = p.asString
        return s.takeIf { it.length <= maxLength && it.none(Char::isISOControl) }
    }

    fun boolean(obj: JsonObject?, name: String): Boolean? {
        val p = primitive(obj, name) ?: return null
        return if (p.isBoolean) p.asBoolean else null
    }

    /** Request id for the answer; 0 if missing or invalid (the client then cannot match it, nothing else). */
    fun req(obj: JsonObject?): Long = long(obj, "req", 0, Int.MAX_VALUE.toLong()) ?: 0
}
