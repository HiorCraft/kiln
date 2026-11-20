package dev.kiln.example.server.shop.lua

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/*
 * Messages of the Lua shop extension `shop` (channels `shop:<name>`, JSON via Gson). Server -> client messages are
 * plain data classes; client -> server requests arrive as raw [JsonObject]s and are parsed field by field with
 * [RequestFields], because client input is untrusted (missing fields, wrong types, fractions, huge numbers).
 *
 * S->C  open    ShopState(open = true, tab)      opens the screen with the full state
 * S->C  state   ShopState(open = false)          live update for open shops (offers, balance, own offers)
 * S->C  result  ShopResult                       answer to a request (same `req`)
 * C->S  open    {}                               player wants the shop (keybind) -> server answers with open
 * C->S  closed  {}                               screen closed -> no more live updates
 * C->S  buy      {req, offer, amount, price}                                    price = unit price the player saw
 * C->S  sell     {req, slot, item, amount, price, duration, category, notify}   slot 0..35, item = item id there
 * C->S  withdraw {req, offer}
 * C->S  reprice  {req, offer, price}
 */

/** One market offer for the client. [item] = `ExtensionItems.encode` of the single-item template. */
data class OfferDto(
    val id: Long,
    val item: String,
    val name: String,
    val key: String,
    val cat: String,
    val price: Long,
    val amount: Int,
    val seller: String,
    val own: Boolean,
    /** Creation time (epoch millis) for "newest first". */
    val created: Long,
    /** Milliseconds until the offer expires; `null` = never. */
    val expiresIn: Long?,
)

/** One of the player's own offers (also expired ones, which are no longer in the market). */
data class MyOfferDto(
    val id: Long,
    val item: String,
    val name: String,
    val key: String,
    val cat: String,
    val price: Long,
    val amount: Int,
    val expiresIn: Long?,
    val expired: Boolean,
)

data class LabelDto(val id: String, val label: String)

data class ShopState(
    val open: Boolean,
    /** Tab to show when opening: `market`, `sell` or `mine`; `null` = keep. */
    val tab: String?,
    val balance: Long,
    val startBalance: Long,
    val maxPrice: Long,
    val categories: List<LabelDto>,
    val durations: List<LabelDto>,
    val offers: List<OfferDto>,
    val mine: List<MyOfferDto>,
    /** Suggested category per item id (`minecraft:oak_log`) of the items in the player's inventory. */
    val suggest: Map<String, String>,
    /** `true` if not all offers fit into one message (the newest are sent). */
    val truncated: Boolean,
)

/**
 * Answer to a request. [error] is shown to the player; [fields] maps form fields (`amount`, `price`, `item`,
 * `category`) to messages; [newPrice] is set when a purchase failed because the price changed.
 */
data class ShopResult(
    val req: Long,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
    val fields: Map<String, String>? = null,
    val newPrice: Long? = null,
)

/** Strict accessors for untrusted request fields: `null` when missing, of the wrong type or out of range. */
object RequestFields {
    private fun primitive(obj: JsonObject?, name: String): com.google.gson.JsonPrimitive? {
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
