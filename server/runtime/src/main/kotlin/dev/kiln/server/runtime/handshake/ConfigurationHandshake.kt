package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.message.Hello
import dev.kiln.protocol.message.Message
import dev.kiln.protocol.message.ServerboundMessage
import dev.kiln.protocol.message.Welcome
import dev.kiln.server.runtime.extension.ExtensionChunker
import dev.kiln.server.runtime.extension.ExtensionPackage

/**
 * Runs the configuration-phase handshake for one client (blocking; called from Paper's
 * `AsyncPlayerConnectionConfigureEvent` thread). See `docs/lua-extensions.md` "Entscheidungen".
 */
class ConfigurationHandshake(
    private val link: HandshakeLink,
    private val inbox: HandshakeInbox,
    private val packages: List<ExtensionPackage>,
    private val timeouts: HandshakeTimeouts = HandshakeTimeouts(),
    /** Bandwidth limit for the chunks; pacing happens on this (handshake) thread, never on the server thread. */
    private val throttle: SendThrottle = SendThrottle.UNLIMITED,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    /** Dependency problems of the registered extensions: every client is disconnected after the version check. */
    private val dependencyProblems: List<String> = emptyList(),
    /** Asked with the bytes about to be streamed (after consent); `false` = download limit of this client reached. */
    private val allowDownload: (Long) -> Boolean = { true },
) {
    /** Every step either returns its result or ends the handshake by throwing [HandshakeAbort]. */
    fun run(): HandshakeResult = try {
        val hello = awaitHello()
        checkCompatible(hello)
        awaitChannel()
        send(Welcome(PROTOCOL_VERSION))
        send(ExtensionManifest(packages.map { it.info }))
        val requested = awaitRequestedPackages()
        val downloadMillis = downloadTimeoutMillis(requested)
        val start = clock()
        stream(requested, start)
        awaitReady(start + downloadMillis, downloadMillis)
    } catch (abort: HandshakeAbort) {
        abort.result
    }

    private fun awaitHello(): Hello = when (val first = await(clock() + timeouts.helloMillis, "Hello")) {
        null -> abort(HandshakeResult.Kick(KickMessages.NO_RUNTIME, "no kiln Hello within ${timeouts.helloMillis} ms"))
        is Hello -> first
        else -> abort(unexpected(first, "Hello"))
    }

    /** Same protocol version, and the registered extensions can be delivered at all. */
    private fun checkCompatible(hello: Hello) {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            // tell the client our version so it can show a precise message (only possible with a registered channel)
            if (link.channelRegistered()) send(Welcome(PROTOCOL_VERSION))
            abort(
                HandshakeResult.Kick(
                    KickMessages.versionMismatch(hello.protocolVersion),
                    "protocol mismatch (client ${hello.protocolVersion}, server $PROTOCOL_VERSION)",
                ),
            )
        }
        if (dependencyProblems.isNotEmpty()) {
            abort(HandshakeResult.Kick(KickMessages.dependencyProblems(dependencyProblems), "extension dependency problems: $dependencyProblems"))
        }
    }

    private fun awaitChannel() {
        val deadline = clock() + timeouts.channelMillis
        while (!link.channelRegistered()) {
            if (!link.isOpen()) abort(HandshakeResult.Closed)
            if (clock() >= deadline) abort(HandshakeResult.Kick(KickMessages.CHANNEL_NOT_REGISTERED, "channel not registered after Hello"))
            sleep(CHANNEL_POLL_MILLIS)
        }
    }

    /** The client answers only after the player consented: no chunk is sent before this request. */
    private fun awaitRequestedPackages(): List<ExtensionPackage> {
        val request = when (val message = await(clock() + timeouts.consentMillis, "ExtensionRequest")) {
            null -> abort(timeout("ExtensionRequest", timeouts.consentMillis, KickMessages.CONSENT_TIMEOUT))
            is ExtensionRequest -> message
            else -> abort(unexpected(message, "ExtensionRequest"))
        }
        val known = packages.associateBy { it.id }
        request.ids.firstOrNull { it !in known }?.let {
            abort(HandshakeResult.Kick(KickMessages.protocolError("unbekannte Erweiterung '$it' angefordert"), "requested unknown extension '$it'"))
        }
        val requested = request.ids.toSet()
        val toSend = packages.filter { it.id in requested }
        val totalBytes = toSend.totalBytes()
        if (!allowDownload(totalBytes)) abort(HandshakeResult.Kick(KickMessages.DOWNLOAD_LIMIT, "download limit reached ($totalBytes bytes requested)"))
        return toSend
    }

    /**
     * The throttle's own transfer time is added, so a low rate cannot cause the download timeout by itself; a
     * resource reload follows on the client whenever any package (cached or not) has assets.
     */
    private fun downloadTimeoutMillis(toSend: List<ExtensionPackage>): Long {
        val reloadMillis = if (packages.any { it.hasAssets }) timeouts.resourceReloadMillis else 0L
        return throttle.downloadTimeoutMillis(timeouts.extensionsMillis, toSend.totalBytes()) + reloadMillis
    }

    /** Sends the chunks of [toSend], paced by the throttle from [start] on. */
    private fun stream(toSend: List<ExtensionPackage>, start: Long) {
        var sentBytes = 0L
        for (pkg in toSend) {
            for (chunk in ExtensionChunker.split(pkg.id, pkg.zip)) {
                waitUntil(start + throttle.sendOffsetMillis(sentBytes))
                if (!link.isOpen()) abort(HandshakeResult.Closed)
                send(chunk)
                sentBytes += chunk.data.size
            }
        }
    }

    private fun awaitReady(deadline: Long, downloadMillis: Long): HandshakeResult {
        val ready = when (val message = await(deadline, "ExtensionsReady")) {
            null -> return timeout("ExtensionsReady", downloadMillis, KickMessages.TIMEOUT)
            is ExtensionsReady -> message
            else -> return unexpected(message, "ExtensionsReady")
        }
        return if (ready.ok) {
            HandshakeResult.Success
        } else {
            HandshakeResult.Kick(KickMessages.clientError(ready.error), "client failed to load extensions: ${ready.error}")
        }
    }

    private fun List<ExtensionPackage>.totalBytes(): Long = sumOf { it.zip.size.toLong() }

    private fun abort(result: HandshakeResult): Nothing = throw HandshakeAbort(result)

    private fun send(message: ClientboundMessage) {
        try {
            link.send(message)
        } catch (e: Exception) {
            if (!link.isOpen()) abort(HandshakeResult.Closed)
            abort(HandshakeResult.Kick(KickMessages.INTERNAL_ERROR, "sending ${message::class.simpleName} failed: $e"))
        }
    }

    /** Sleeps until [time] (throttled sending) in steps of at most `pollMillis`; aborts on a closed connection. */
    private fun waitUntil(time: Long) {
        while (true) {
            if (!link.isOpen()) abort(HandshakeResult.Closed)
            val remaining = time - clock()
            if (remaining <= 0) return
            sleep(minOf(remaining, timeouts.pollMillis))
        }
    }

    /** Next message, or `null` at [deadline]; aborts on a closed connection, malformed data or overflow. */
    private fun await(deadline: Long, expected: String): ServerboundMessage? {
        while (true) {
            if (!link.isOpen()) abort(HandshakeResult.Closed)
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            when (val incoming = inbox.poll(minOf(remaining, timeouts.pollMillis))) {
                null -> continue
                is HandshakeIncoming.Message -> return incoming.message
                is HandshakeIncoming.Malformed -> abort(
                    HandshakeResult.Kick(KickMessages.protocolError(incoming.error), "malformed message while waiting for $expected: ${incoming.error}"),
                )
                HandshakeIncoming.Overflow -> abort(
                    HandshakeResult.Kick(KickMessages.protocolError("zu viele Nachrichten"), "message flood while waiting for $expected"),
                )
            }
        }
    }

    private fun unexpected(message: ServerboundMessage, expected: String): HandshakeResult.Kick =
        HandshakeResult.Kick(
            KickMessages.protocolError("erwartet $expected, erhalten ${message::class.simpleName}"),
            "unexpected ${message::class.simpleName}, expected $expected",
        )

    private fun timeout(expected: String, millis: Long, reason: String): HandshakeResult.Kick =
        HandshakeResult.Kick(reason, "timeout after $millis ms waiting for $expected")

    private companion object {
        const val CHANNEL_POLL_MILLIS = 50L
    }
}
