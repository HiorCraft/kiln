package dev.kiln.server.runtime.extension

/** A package that failed validation; the message is meant for server admins / plugin developers. */
class InvalidExtensionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
