package dev.kiln.example.server.shop

/** Set [slot] to [newAmount] of the moved item (`0` = empty the slot); [wasEmpty] = create a new stack there. */
data class SlotChange(val slot: Int, val newAmount: Int, val wasEmpty: Boolean)
