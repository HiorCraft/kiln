package dev.kiln.server.runtime

import dev.kiln.server.runtime.handshake.DownloadBudget
import dev.kiln.server.runtime.handshake.HandshakeSlots
import dev.kiln.server.runtime.handshake.HandshakeTimeouts
import dev.kiln.server.runtime.handshake.SendThrottle
import org.bukkit.configuration.ConfigurationSection
import java.util.Properties
import java.util.logging.LogRecord
import java.util.logging.Logger

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
                    helloMillis = config.getLong("hello-timeout-seconds", t.helloMillis / 1_000).coerceIn(1, 120) * 1_000,
                    consentMillis = config.getLong("consent-timeout-seconds", t.consentMillis / 1_000).coerceIn(10, 3_600) * 1_000,
                    extensionsMillis = config.getLong("extensions-timeout-seconds", t.extensionsMillis / 1_000).coerceIn(5, 600) * 1_000,
                    resourceReloadMillis = config.getLong("resource-reload-timeout-seconds", t.resourceReloadMillis / 1_000).coerceIn(10, 900) * 1_000,
                ),
                extensionSendRateKibPerSecond = config.getLong("extension-send-rate-kib-per-second", d.extensionSendRateKibPerSecond).coerceAtLeast(0),
                maxHandshakesPerAddress = config.getInt("max-handshakes-per-address", d.maxHandshakesPerAddress).coerceAtLeast(0),
                downloadMibPerAddress = config.getLong("download-mib-per-address", d.downloadMibPerAddress).coerceAtLeast(0),
                downloadWindowMinutes = config.getLong("download-window-minutes", d.downloadWindowMinutes).coerceIn(1, 1_440),
            )
        }
    }
}

/** Versions this Kiln build was made for (generated into `kiln-build.properties`). */
data class BuildInfo(val minecraft: String, val kotlin: String) {
    companion object {
        fun load(): BuildInfo {
            val props = Properties()
            BuildInfo::class.java.getResourceAsStream("/dev/kiln/server/runtime/kiln-build.properties")?.use(props::load)
            return BuildInfo(props.getProperty("minecraft", "?"), props.getProperty("kotlin", "?"))
        }
    }
}

/**
 * What must hold before Kiln starts: it uses server internals of exactly the Minecraft version it was built for, and
 * (embedded) runs on the host's Kotlin, which must be at least the major.minor it was compiled with.
 */
object RuntimeChecks {
    fun problems(build: BuildInfo, serverMinecraft: String, runtimeKotlin: KotlinVersion): List<String> {
        val result = ArrayList<String>()
        if (serverMinecraft != build.minecraft) {
            result += "Kiln is built for Minecraft ${build.minecraft} (server internals), this server runs $serverMinecraft"
        }
        val parts = build.kotlin.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size >= 2 && !runtimeKotlin.isAtLeast(parts[0], parts[1])) {
            result += "Kiln needs Kotlin ${parts[0]}.${parts[1]} or newer at runtime, found $runtimeKotlin"
        }
        return result
    }
}

/** Logger that prefixes every message with `[Kiln] ` and hands it to [parent] (the host plugin's logger). */
class PrefixedLogger(parent: Logger) : Logger(parent.name, null) {
    init {
        setParent(parent)
        level = parent.level
    }

    override fun log(record: LogRecord) {
        record.message = "[Kiln] " + record.message
        super.log(record)
    }
}
