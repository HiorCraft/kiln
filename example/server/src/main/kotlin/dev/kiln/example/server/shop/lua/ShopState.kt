package dev.kiln.example.server.shop.lua

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
