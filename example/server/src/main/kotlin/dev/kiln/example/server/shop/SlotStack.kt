package dev.kiln.example.server.shop

/** One non-empty slot: whether it holds the moved kind of item ([similar]) and how many. */
data class SlotStack(val similar: Boolean, val amount: Int)
