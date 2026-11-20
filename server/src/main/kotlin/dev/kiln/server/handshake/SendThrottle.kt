package dev.kiln.server.handshake

/**
 * Bandwidth limit for streaming extension chunks to one player (`extension-send-rate-kib-per-second`).
 * Pure schedule arithmetic, no clock: the handshake sends a chunk once [sendOffsetMillis] of the bytes already sent
 * has elapsed since the download started. The first chunk goes out at once, so the transfer of `n` bytes takes about
 * `(n - last chunk) / rate`; [transferMillis] (`n / rate`) is used as an upper bound for the download timeout.
 *
 * @param bytesPerSecond `0` = unlimited.
 */
class SendThrottle(val bytesPerSecond: Long) {
    init {
        require(bytesPerSecond >= 0) { "bytesPerSecond must be >= 0, was $bytesPerSecond" }
    }

    val unlimited: Boolean get() = bytesPerSecond == 0L

    /** Milliseconds after the start of the download at which the next chunk may be sent, given [bytesSentBefore]. */
    fun sendOffsetMillis(bytesSentBefore: Long): Long {
        require(bytesSentBefore >= 0) { "negative byte count" }
        if (unlimited || bytesSentBefore == 0L) return 0
        // ceil(bytes * 1000 / rate) without overflow for any realistic size (packages are <= 4 MiB each)
        return Math.ceilDiv(Math.multiplyExact(bytesSentBefore, 1_000L), bytesPerSecond)
    }

    /** Upper bound for streaming [totalBytes] at this rate (`0` when unlimited). */
    fun transferMillis(totalBytes: Long): Long = sendOffsetMillis(totalBytes)

    /**
     * Time between `ExtensionRequest` and `ExtensionsReady`: the configured [baseMillis] (verification, loading and
     * an unthrottled network transfer) plus the time the throttle itself needs for [totalBytes].
     */
    fun downloadTimeoutMillis(baseMillis: Long, totalBytes: Long): Long = baseMillis + transferMillis(totalBytes)

    override fun toString(): String = if (unlimited) "unlimited" else "${bytesPerSecond / 1024} KiB/s"

    companion object {
        val UNLIMITED = SendThrottle(0)

        /** From the config value; negative values count as unlimited. */
        fun kibPerSecond(kib: Long): SendThrottle = if (kib <= 0) UNLIMITED else SendThrottle(Math.multiplyExact(kib, 1024L))
    }
}
