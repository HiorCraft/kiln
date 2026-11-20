package dev.kiln.server.runtime.handshake

/**
 * Extension download bytes per client address and fixed window: a client can request every package again on each
 * join, so reconnecting in a loop would otherwise use up the server's upload. A download larger than the whole budget
 * is still allowed when it is the first one in the window (a server's own packages must never lock everyone out).
 * `0` = unlimited. Thread-safe.
 */
class DownloadBudget(
    private val bytesPerWindow: Long,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val windows = HashMap<String, DownloadWindow>()

    @Synchronized
    fun tryConsume(address: String, bytes: Long): Boolean {
        if (bytesPerWindow <= 0 || bytes <= 0) return true
        val now = clock()
        val window = windows.getOrPut(address) { DownloadWindow(now, 0) }
        if (now - window.start >= windowMillis) {
            window.start = now
            window.used = 0
        }
        if (window.used > 0 && window.used + bytes > bytesPerWindow) return false
        window.used += bytes
        return true
    }

    /** Drops windows that are over (called now and then). */
    @Synchronized
    fun sweep() {
        val now = clock()
        windows.values.removeIf { now - it.start >= windowMillis }
    }
}
