package dev.kiln.client.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import java.util.Base64

/**
 * Opaque Lua handle of a decoded item ([ItemRef]). Lua sees a userdata whose metatable is locked
 * (`getmetatable(h) == "item"`) and offers the read-only fields `h.id`, `h.name`, `h.maxStack`; nothing else of the
 * stack is reachable. Handles are immutable and may be shared with other extensions.
 */
class ItemHandle internal constructor(val ref: ItemRef) : LuaUserdata(ref, METATABLE) {
    override fun setmetatable(metatable: LuaValue?): LuaValue = throw LuaError("cannot change the metatable of an item handle")

    override fun tojstring(): String = "item(${ref.id})"

    companion object {
        private val METATABLE: LuaTable = LuaTable().apply {
            set("__metatable", "item")
            set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val ref = (args.arg1() as? ItemHandle)?.ref ?: return LuaValue.NIL
                    return when (args.arg(2).optjstring("")) {
                        "id" -> LuaValue.valueOf(ref.id)
                        "name" -> LuaValue.valueOf(ref.name)
                        "maxStack" -> LuaValue.valueOf(ref.maxStackSize)
                        else -> LuaValue.NIL
                    }
                }
            })
            set("__tostring", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = LuaValue.valueOf(args.arg1().tojstring())
            })
        }
    }
}

/**
 * `kiln.items.decode(text)` of one extension: Base64 of Paper's `ItemStack.serializeAsBytes()` (server:
 * `ExtensionItems.encode`) -> [ItemHandle].
 *
 * Limits: text at most [MAX_TEXT] chars (64 KiB of NBT), must be gzip data; results are cached by text in an LRU of
 * [CACHE_SIZE] entries (the same text gives the same handle); at most [MAX_DECODES_PER_SECOND] cache misses per
 * second (decoding NBT is not free) - more is a Lua error. Undecodable items return `nil, reason`.
 */
class LuaItems(private val decoder: (ByteArray) -> ItemRef?, private val clock: () -> Long = System::nanoTime) {
    private val cache = object : LinkedHashMap<String, LuaValue>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LuaValue>?): Boolean = size > CACHE_SIZE
    }
    private val decodes = ArrayDeque<Long>()

    val cached: Int get() = cache.size

    fun decode(text: String): Varargs {
        cache[text]?.let { return it }
        if (text.length > MAX_TEXT) throw LuaError("item data too long (limit $MAX_TEXT characters)")
        val now = clock()
        while (decodes.isNotEmpty() && now - decodes.first() >= 1_000_000_000L) decodes.removeFirst()
        if (decodes.size >= MAX_DECODES_PER_SECOND) throw LuaError("too many item decodes (limit $MAX_DECODES_PER_SECOND per second; cache the handles)")
        decodes.addLast(now)
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("not Base64"))
        }
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("not gzip NBT (expected ExtensionItems.encode)"))
        }
        val ref = decoder(bytes) ?: return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("cannot decode item"))
        val handle = ItemHandle(ref)
        cache[text] = handle
        return handle
    }

    companion object {
        const val MAX_TEXT = 87_384
        const val CACHE_SIZE = 512
        const val MAX_DECODES_PER_SECOND = 256

        /** The [ItemRef] of a Lua value that must be an item handle. */
        fun ref(value: LuaValue, what: String = "item"): ItemRef =
            (value as? ItemHandle)?.ref ?: throw LuaError("$what must be an item handle (kiln.items.decode), got ${value.typename()}")
    }
}
