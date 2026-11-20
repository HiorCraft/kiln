package dev.kiln.client.ext.handshake

/** Where one announced extension stands in an [ExtensionHandshake]. */
enum class HandshakeItemStatus { QUEUED, CACHED, DOWNLOADING, VERIFYING, STARTING, DONE, FAILED }
