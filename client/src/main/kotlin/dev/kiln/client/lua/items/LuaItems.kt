package dev.kiln.client.lua.items

import dev.kiln.client.lua.NANOS_PER_SECOND
import dev.kiln.client.lua.SlidingWindowLimit
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import java.util.Base64

/**
 * `kiln.items.decode(text)` of one extension: Base64 of Paper's `ItemStack.serializeAsBytes()` (server:
 * `ExtensionItems.encode`) -> [ItemHandle].
 *
 * Limits: text at most [MAX_TEXT] chars (64 KiB of NBT), must be gzip data; results are cached by text in an LRU of
 * [CACHE_SIZE] entries (the same text gives the same handle); at most [MAX_DECODES_PER_SECOND] cache misses per
 * second (decoding NBT is not free) - more is a Lua error. Undecodable items return `nil, reason`.
 */
class LuaItems(private val decoder: (ByteArray) -> ItemRef?, clock: () -> Long = System::nanoTime) {
    private val cache = object : LinkedHashMap<String, LuaValue>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LuaValue>?): Boolean = size > CACHE_SIZE
    }
    private val decodeLimit = SlidingWindowLimit(MAX_DECODES_PER_SECOND, NANOS_PER_SECOND, clock)

    val cached: Int get() = cache.size

    fun decode(text: String): Varargs {
        cache[text]?.let { return it }
        if (text.length > MAX_TEXT) throw LuaError("item data too long (limit $MAX_TEXT characters)")
        if (!decodeLimit.tryAcquire()) throw LuaError("too many item decodes (limit $MAX_DECODES_PER_SECOND per second; cache the handles)")
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("not Base64"))
        }
        if (!isGzip(bytes)) {
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("not gzip NBT (expected ExtensionItems.encode)"))
        }
        val ref = decoder(bytes) ?: return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("cannot decode item"))
        val handle = ItemHandle(ref)
        cache[text] = handle
        return handle
    }

    private fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == GZIP_MAGIC_1 && bytes[1] == GZIP_MAGIC_2

    companion object {
        const val MAX_TEXT = 87_384
        const val CACHE_SIZE = 512
        const val MAX_DECODES_PER_SECOND = 256
        private const val GZIP_MAGIC_1 = 0x1f.toByte()
        private const val GZIP_MAGIC_2 = 0x8b.toByte()

        /** The [ItemRef] of a Lua value that must be an item handle. */
        fun ref(value: LuaValue, what: String = "item"): ItemRef =
            (value as? ItemHandle)?.ref ?: throw LuaError("$what must be an item handle (kiln.items.decode), got ${value.typename()}")
    }
}
