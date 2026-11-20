package dev.kiln.server.runtime.handshake

/** Ends a [ConfigurationHandshake] early with [result] (no stack trace: this is control flow, not an error). */
internal class HandshakeAbort(val result: HandshakeResult) : Exception(null, null, false, false)
