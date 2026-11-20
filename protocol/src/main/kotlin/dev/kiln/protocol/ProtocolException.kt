package dev.kiln.protocol

/** Thrown for every malformed, oversized or otherwise invalid message. Callers log it and drop the message. */
public class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
