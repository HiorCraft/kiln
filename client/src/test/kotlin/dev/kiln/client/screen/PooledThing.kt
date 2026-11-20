package dev.kiln.client.screen

/** A stateful object kept by a [KeyedPool] in tests. */
internal class PooledThing(val id: String, var kind: String, var updates: Int = 0)
