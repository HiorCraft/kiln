package dev.kiln.client.lua.sandbox

import dev.kiln.client.lua.NANOS_PER_SECOND

/**
 * Lua's `print` with at most [maxPerSecond] lines per one-second window; the first line over the limit is replaced
 * by one note, the rest of the window is dropped, so a script cannot fill the log.
 */
internal class PrintThrottle(private val maxPerSecond: Int, private val clock: () -> Long, private val sink: (String) -> Unit) {
    private var windowStart = Long.MIN_VALUE
    private var linesInWindow = 0

    fun print(line: String) {
        val now = clock()
        if (windowStart == Long.MIN_VALUE || now - windowStart >= NANOS_PER_SECOND) {
            windowStart = now
            linesInWindow = 0
        }
        linesInWindow++
        when {
            linesInWindow <= maxPerSecond -> sink(line)
            linesInWindow == maxPerSecond + 1 -> sink("(more output suppressed: at most $maxPerSecond lines per second)")
        }
    }
}
