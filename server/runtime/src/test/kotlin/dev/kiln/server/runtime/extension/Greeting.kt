package dev.kiln.server.runtime.extension

/** A message type extension channels bind JSON to in [ExtensionJsonTest]. */
data class Greeting(val text: String, val count: Int = 0, val tags: List<String> = emptyList())
