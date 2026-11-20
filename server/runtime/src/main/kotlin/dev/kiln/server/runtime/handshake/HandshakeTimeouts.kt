package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady

data class HandshakeTimeouts(
    val helloMillis: Long = 10_000,
    val channelMillis: Long = 5_000,
    /**
     * Manifest -> `ExtensionRequest`: the client shows its consent screen in between (nothing is downloaded before
     * consent), so the player gets time to read it.
     */
    val consentMillis: Long = 300_000,
    /** `ExtensionRequest` -> `ExtensionsReady`: download, verification and loading. */
    val extensionsMillis: Long = 60_000,
    /**
     * Added to [extensionsMillis] when a package has a resource-pack tree: the client reloads its resources (all
     * models, textures, sounds) before it reports `ExtensionsReady`, which takes long on slow or heavily modded clients.
     */
    val resourceReloadMillis: Long = 120_000,
    /** Granularity for noticing a closed connection while waiting. */
    val pollMillis: Long = 250,
)
