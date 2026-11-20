package dev.kiln.server.runtime

import dev.kiln.server.runtime.handshake.DownloadBudget
import dev.kiln.server.runtime.handshake.HandshakeSlots
import dev.kiln.server.runtime.handshake.HandshakeTimeouts
import dev.kiln.server.runtime.handshake.SendThrottle
import org.bukkit.configuration.ConfigurationSection

/**
 * Settings of a Kiln runtime. The standalone plugin reads them from its `config.yml` ([from]); a host that embeds Kiln
 * ([KilnEmbedded]) passes them in (its own config or the defaults).
 */
data class KilnSettings(
    /** Logs every dropped / ignored client message. */
    val debug: Boolean = false,
    /** Max client messages per player and second; the excess is dropped. */
    val rateLimitPerSecond: Int = 100,
    /** Max Lua extension channel messages per player and second; the excess is dropped. */
    val extensionMessagesPerSecond: Int = 40,
    val timeouts: HandshakeTimeouts = HandshakeTimeouts(),
    /** Per-player bandwidth limit for extension chunks in KiB/s (0 = unlimited). */
    val extensionSendRateKibPerSecond: Long = 0,
    /** Concurrent configuration-phase handshakes per client address (0 = unlimited). */
    val maxHandshakesPerAddress: Int = 4,
    /** Extension download budget per client address and [downloadWindowMinutes] (0 = unlimited). */
    val downloadMibPerAddress: Long = 256,
    val downloadWindowMinutes: Long = 10,
) {
    val sendThrottle: SendThrottle get() = SendThrottle.kibPerSecond(extensionSendRateKibPerSecond)

    fun handshakeSlots(): HandshakeSlots = HandshakeSlots(maxHandshakesPerAddress)

    fun downloadBudget(): DownloadBudget = DownloadBudget(downloadMibPerAddress * 1024 * 1024, downloadWindowMinutes * 60_000)

    companion object {
        /** Reads the keys of Kiln's `config.yml` from [config] (missing keys: defaults; values are clamped). */
        fun from(config: ConfigurationSection): KilnSettings {
            val d = KilnSettings()
            val t = d.timeouts
            return KilnSettings(
                debug = config.getBoolean("debug", d.debug),
                rateLimitPerSecond = config.getInt("rate-limit-per-second", d.rateLimitPerSecond).coerceAtLeast(1),
                extensionMessagesPerSecond = config.getInt("extension-messages-per-second", d.extensionMessagesPerSecond).coerceAtLeast(1),
                timeouts = HandshakeTimeouts(
                    helloMillis = config.millisFromSeconds("hello-timeout-seconds", t.helloMillis, 1L..120),
                    consentMillis = config.millisFromSeconds("consent-timeout-seconds", t.consentMillis, 10L..3_600),
                    extensionsMillis = config.millisFromSeconds("extensions-timeout-seconds", t.extensionsMillis, 5L..600),
                    resourceReloadMillis = config.millisFromSeconds("resource-reload-timeout-seconds", t.resourceReloadMillis, 10L..900),
                ),
                extensionSendRateKibPerSecond = config.getLong("extension-send-rate-kib-per-second", d.extensionSendRateKibPerSecond).coerceAtLeast(0),
                maxHandshakesPerAddress = config.getInt("max-handshakes-per-address", d.maxHandshakesPerAddress).coerceAtLeast(0),
                downloadMibPerAddress = config.getLong("download-mib-per-address", d.downloadMibPerAddress).coerceAtLeast(0),
                downloadWindowMinutes = config.getLong("download-window-minutes", d.downloadWindowMinutes).coerceIn(1, 1_440),
            )
        }

        /** A duration configured in seconds under [key] (default [defaultMillis]), clamped to [seconds], in millis. */
        private fun ConfigurationSection.millisFromSeconds(key: String, defaultMillis: Long, seconds: LongRange): Long =
            getLong(key, defaultMillis / 1_000).coerceIn(seconds) * 1_000
    }
}
