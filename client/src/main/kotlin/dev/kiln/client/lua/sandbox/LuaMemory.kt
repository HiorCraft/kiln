package dev.kiln.client.lua.sandbox

import dev.kiln.client.lua.exports.ReadOnlyProxy
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Rough estimate of the Lua heap reachable from a set of roots: tables (array + hash slots, keys, values,
 * metatables), closures (upvalues; prototypes are shared code and not counted), strings (bytes). The JVM has no
 * per-object-graph accounting, so this walk is the "grober Allokationszähler" of the spec. Read-only proxies of other
 * extensions' exports ([ReadOnlyProxy]) are leaves (their content belongs to the exporter); item handles count a fixed
 * [ITEM_HANDLE] each (the decoded stack lives on the Java heap). The walk stops as soon as
 * the byte or object limit is exceeded, so its cost is bounded by the limits themselves.
 */
object LuaMemory {
    private const val TABLE_OVERHEAD = 64L
    private const val SLOT = 16L
    private const val STRING_OVERHEAD = 40L
    private const val CLOSURE_OVERHEAD = 48L
    private const val UPVALUE = 24L
    private const val ITEM_HANDLE = 1_024L

    fun estimate(roots: Iterable<LuaValue>, maxBytes: Long, maxObjects: Int): MemoryEstimate {
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque<LuaValue>()
        var bytes = 0L
        fun visit(value: LuaValue?) {
            if (value == null) return
            when (value) {
                is ReadOnlyProxy -> {}
                is LuaTable, is LuaClosure, is LuaString, is LuaUserdata -> if (seen.put(value, Unit) == null) queue.add(value)
                else -> {}
            }
        }
        roots.forEach(::visit)
        while (queue.isNotEmpty()) {
            if (bytes > maxBytes || seen.size > maxObjects) return MemoryEstimate(bytes, seen.size, true)
            when (val value = queue.poll()) {
                is LuaString -> bytes += STRING_OVERHEAD + value.m_length
                is LuaUserdata -> bytes += ITEM_HANDLE
                is LuaTable -> {
                    bytes += TABLE_OVERHEAD
                    visit(value.getmetatable())
                    var key: LuaValue = LuaValue.NIL
                    while (true) {
                        val next = value.next(key)
                        key = next.arg1()
                        if (key.isnil()) break
                        bytes += SLOT
                        visit(key)
                        visit(next.arg(2))
                        if (bytes > maxBytes) break
                    }
                }
                is LuaClosure -> {
                    bytes += CLOSURE_OVERHEAD
                    for (up in value.upValues) {
                        if (up == null) continue
                        bytes += UPVALUE
                        visit(up.value)
                    }
                }
            }
        }
        return MemoryEstimate(bytes, seen.size, bytes > maxBytes || seen.size > maxObjects)
    }
}
