package dev.kiln.client.lua.json

/** Limits of one message: nesting depth, entries in total, encoded length in characters. */
data class JsonLimits(val maxDepth: Int = 16, val maxEntries: Int = 2_048, val maxChars: Int = 16_384)
