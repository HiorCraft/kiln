package dev.kiln.client.lua

internal const val NANOS_PER_SECOND = 1_000_000_000L

/** At most [maxEvents] events within any window of [windowNanos] (rate limits of Lua API calls). */
internal class SlidingWindowLimit(
    private val maxEvents: Int,
    private val windowNanos: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private val times = ArrayDeque<Long>()

    /** Forgets events that left the window; `true` if one more event fits. */
    fun hasRoom(): Boolean {
        val now = clock()
        while (times.isNotEmpty() && now - times.first() >= windowNanos) times.removeFirst()
        return times.size < maxEvents
    }

    fun record() {
        times.addLast(clock())
    }

    /** Records an event if it fits; `false` (nothing recorded) if the limit is reached. */
    fun tryAcquire(): Boolean {
        if (!hasRoom()) return false
        record()
        return true
    }
}
