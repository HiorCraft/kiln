package dev.kiln.client.ext.handshake

/** Where an [ExtensionHandshake] stands; [DONE], [FAILED] and [DECLINED] are final. */
enum class HandshakePhase { WAIT_WELCOME, WAIT_MANIFEST, CONSENT, DOWNLOADING, LOADING, RELOADING, DONE, FAILED, DECLINED }
