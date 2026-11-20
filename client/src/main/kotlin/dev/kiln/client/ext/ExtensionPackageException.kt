package dev.kiln.client.ext

/** A package violates the format rules; [message] is shown to the player (and sent to the server). */
class ExtensionPackageException(message: String) : Exception(message)
