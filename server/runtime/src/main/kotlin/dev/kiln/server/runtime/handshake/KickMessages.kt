package dev.kiln.server.runtime.handshake

import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.PROTOCOL_VERSION

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
