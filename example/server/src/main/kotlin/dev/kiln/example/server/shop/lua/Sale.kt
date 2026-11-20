package dev.kiln.example.server.shop.lua

import dev.kiln.example.server.shop.Offer

/** A completed purchase, for notifying the seller. */
data class Sale<I>(val offer: Offer<I>, val amount: Int, val total: Long, val buyerName: String)
