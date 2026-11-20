package dev.kiln.server

/**
 * Fixed-window rate limit: at most [maxPerWindow] messages per [windowMillis]; the excess is rejected.
 * Not thread-safe; one instance per player, used on the thread that receives that player's messages.
 */
class RateLimiter(
    private val maxPerWindow: Int,
    private val windowMillis: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var windowStart = Long.MIN_VALUE
    private var count = 0

    /** Number of rejected messages in the current window. */
    var rejectedInWindow: Int = 0
        private set

    /** Counts one message; `false` if it exceeds the limit and must be dropped. */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMillis) {
            windowStart = now
            count = 0
            rejectedInWindow = 0
        }
        if (count >= maxPerWindow) {
            rejectedInWindow++
            return false
        }
        count++
        return true
    }
}
