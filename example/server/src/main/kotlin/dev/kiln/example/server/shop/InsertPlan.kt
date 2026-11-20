package dev.kiln.example.server.shop

data class InsertPlan(val changes: List<SlotChange>, val leftover: Int)
