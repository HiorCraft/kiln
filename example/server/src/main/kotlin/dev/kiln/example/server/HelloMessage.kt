package dev.kiln.example.server

/** Message on `kiln_example:hello` (both directions). Client input: [text] may be missing. */
data class HelloMessage(val text: String?)
