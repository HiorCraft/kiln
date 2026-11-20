package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.ExtensionManifest
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.Hello
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.Welcome
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.server.runtime.extension.ExtensionChunker
import dev.kiln.server.runtime.extension.ExtensionPackage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The configuration-phase connection as the handshake sees it (Paper glue or a test fake). */
interface HandshakeLink {
    /** Sends immediately; may throw if the connection is gone. */
    fun send(message: ClientboundMessage)

    fun isOpen(): Boolean

    /** Whether the client announced [dev.kiln.protocol.CHANNEL] via `minecraft:register`. */
    fun channelRegistered(): Boolean
}

/** Player-facing disconnect texts. */
object KickMessages {
    const val NO_RUNTIME =
        "Dieser Server benötigt die Client-Mod kiln (Protokoll v$PROTOCOL_VERSION).\n" +
            "Bitte installiere sie (Fabric) und verbinde dich erneut."

    fun versionMismatch(client: Int): String =
        "kiln-Version passt nicht: Server spricht Protokoll v$PROTOCOL_VERSION, dein Client v$client.\n" +
            "Bitte aktualisiere ${if (client < PROTOCOL_VERSION) "deine kiln-Mod" else "den Server bzw. nutze eine passende Mod-Version"}."

    const val CHANNEL_NOT_REGISTERED =
        "Der kiln-Client hat den Kanal kiln:main nicht angemeldet. Bitte verbinde dich erneut."

    fun protocolError(detail: String): String = "Ungültige kiln-Nachricht: $detail"

    /** `ExtensionRequest` -> `ExtensionsReady` (download, verification, loading) took too long. */
    const val TIMEOUT = "Server-Erweiterungen wurden nicht rechtzeitig geladen (Zeitüberschreitung). Bitte verbinde dich erneut."

    /** Manifest -> `ExtensionRequest`: the player did not answer the consent screen in time. */
    const val CONSENT_TIMEOUT =
        "Keine Antwort auf die Zustimmungsabfrage für die Server-Erweiterungen (Zeit abgelaufen). Bitte verbinde dich erneut."

    fun clientError(error: String?): String =
        "Server-Erweiterungen konnten nicht geladen werden" + (error?.takeIf { it.isNotBlank() }?.let { ":\n$it" } ?: ".")

    const val INTERNAL_ERROR = "Interner Fehler beim Laden der Server-Erweiterungen. Bitte verbinde dich erneut."

    const val NOT_HANDSHAKEN =
        "kiln-Anmeldung fehlt (Server wurde evtl. neu geladen). Bitte verbinde dich erneut."

    const val SHUTDOWN = "Server wird neu gestartet."

    const val TOO_MANY_HANDSHAKES = "Zu viele gleichzeitige Verbindungen von deiner Adresse. Bitte versuche es gleich noch einmal."

    const val DOWNLOAD_LIMIT =
        "Zu viele Downloads der Server-Erweiterungen von deiner Adresse. Bitte versuche es in ein paar Minuten erneut."

    fun dependencyProblems(problems: List<String>): String =
        "Die Server-Erweiterungen sind fehlerhaft konfiguriert (Abhängigkeiten):" + NL + problems.take(3).joinToString(NL) +
            (if (problems.size > 3) NL + "…" else "") + NL + "Bitte melde das dem Server-Team."

    private const val NL = "\n"
}

sealed interface HandshakeResult {
    data object Success : HandshakeResult
    data class Kick(val reason: String, val log: String) : HandshakeResult
    /** The client disconnected (e.g. declined the consent screen). */
    data object Closed : HandshakeResult
}

/** Messages of one configuring client, filled by the plugin-message listener, drained by the handshake. */
class HandshakeInbox(capacity: Int = 16) {
    sealed interface Incoming {
        data class Message(val message: ServerboundMessage) : Incoming
        data class Malformed(val error: String) : Incoming
        data object Overflow : Incoming
    }

    private val queue = LinkedBlockingQueue<Incoming>(capacity + 1)
    private val capacity = capacity

    @Volatile
    var createdAt: Long = System.currentTimeMillis()
        private set

    fun offerBytes(bytes: ByteArray) {
        val incoming = try {
            Incoming.Message(MessageCodec.decodeServerbound(bytes))
        } catch (e: ProtocolException) {
            Incoming.Malformed(e.message ?: "malformed")
        }
        offer(incoming)
    }

    fun offer(incoming: Incoming) {
        synchronized(queue) {
            if (queue.size >= capacity) {
                if (queue.peek() != Incoming.Overflow) {
                    queue.clear()
                    queue.offer(Incoming.Overflow)
                }
                return
            }
            queue.offer(incoming)
        }
    }

    fun poll(timeoutMillis: Long): Incoming? = queue.poll(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
}

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
    private class Abort(val result: HandshakeResult) : Exception(null, null, false, false)

    fun run(): HandshakeResult = try {
        runSteps()
    } catch (abort: Abort) {
        abort.result
    }

    private fun runSteps(): HandshakeResult {
        val hello = when (val first = await(clock() + timeouts.helloMillis, "Hello")) {
            null -> return HandshakeResult.Kick(KickMessages.NO_RUNTIME, "no kiln Hello within ${timeouts.helloMillis} ms")
            is Hello -> first
            else -> return unexpected(first, "Hello")
        }
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            // tell the client our version so it can show a precise message (only possible with a registered channel)
            if (link.channelRegistered()) send(Welcome(PROTOCOL_VERSION))
            return HandshakeResult.Kick(
                KickMessages.versionMismatch(hello.protocolVersion),
                "protocol mismatch (client ${hello.protocolVersion}, server $PROTOCOL_VERSION)",
            )
        }
        if (dependencyProblems.isNotEmpty()) {
            return HandshakeResult.Kick(KickMessages.dependencyProblems(dependencyProblems), "extension dependency problems: $dependencyProblems")
        }
        val channelDeadline = clock() + timeouts.channelMillis
        while (!link.channelRegistered()) {
            if (!link.isOpen()) return HandshakeResult.Closed
            if (clock() >= channelDeadline) {
                return HandshakeResult.Kick(KickMessages.CHANNEL_NOT_REGISTERED, "channel not registered after Hello")
            }
            sleep(CHANNEL_POLL_MILLIS)
        }

        send(Welcome(PROTOCOL_VERSION))
        send(ExtensionManifest(packages.map { it.info }))

        // the client answers only after the player consented: no chunk is sent before this request
        val request = when (val message = await(clock() + timeouts.consentMillis, "ExtensionRequest")) {
            null -> return timeout("ExtensionRequest", timeouts.consentMillis, KickMessages.CONSENT_TIMEOUT)
            is ExtensionRequest -> message
            else -> return unexpected(message, "ExtensionRequest")
        }
        val known = packages.associateBy { it.id }
        request.ids.firstOrNull { it !in known }?.let {
            return HandshakeResult.Kick(KickMessages.protocolError("unbekannte Erweiterung '$it' angefordert"), "requested unknown extension '$it'")
        }
        val requested = request.ids.toSet()
        val toSend = packages.filter { it.id in requested }
        val totalBytes = toSend.sumOf { it.zip.size.toLong() }
        if (!allowDownload(totalBytes)) {
            return HandshakeResult.Kick(KickMessages.DOWNLOAD_LIMIT, "download limit reached ($totalBytes bytes requested)")
        }
        // the throttle's own transfer time is added, so a low rate cannot cause the download timeout by itself
        // a resource reload follows on the client whenever any package (cached or not) has assets
        val reloadMillis = if (packages.any { it.hasAssets }) timeouts.resourceReloadMillis else 0L
        val downloadMillis = throttle.downloadTimeoutMillis(timeouts.extensionsMillis, totalBytes) + reloadMillis
        val start = clock()
        val deadline = start + downloadMillis
        var sentBytes = 0L
        for (pkg in toSend) {
            for (chunk in ExtensionChunker.split(pkg.id, pkg.zip)) {
                waitUntil(start + throttle.sendOffsetMillis(sentBytes))
                if (!link.isOpen()) return HandshakeResult.Closed
                send(chunk)
                sentBytes += chunk.data.size
            }
        }

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

    private fun send(message: ClientboundMessage) {
        try {
            link.send(message)
        } catch (e: Exception) {
            if (!link.isOpen()) throw Abort(HandshakeResult.Closed)
            throw Abort(HandshakeResult.Kick(KickMessages.INTERNAL_ERROR, "sending ${message::class.simpleName} failed: $e"))
        }
    }

    /** Sleeps until [time] (throttled sending) in steps of at most `pollMillis`; aborts on a closed connection. */
    private fun waitUntil(time: Long) {
        while (true) {
            if (!link.isOpen()) throw Abort(HandshakeResult.Closed)
            val remaining = time - clock()
            if (remaining <= 0) return
            sleep(minOf(remaining, timeouts.pollMillis))
        }
    }

    /** Next message, or `null` at [deadline]; aborts on a closed connection, malformed data or overflow. */
    private fun await(deadline: Long, expected: String): ServerboundMessage? {
        while (true) {
            if (!link.isOpen()) throw Abort(HandshakeResult.Closed)
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            when (val incoming = inbox.poll(minOf(remaining, timeouts.pollMillis))) {
                null -> continue
                is HandshakeInbox.Incoming.Message -> return incoming.message
                is HandshakeInbox.Incoming.Malformed -> throw Abort(
                    HandshakeResult.Kick(KickMessages.protocolError(incoming.error), "malformed message while waiting for $expected: ${incoming.error}"),
                )
                HandshakeInbox.Incoming.Overflow -> throw Abort(
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
