package dev.kiln.client.ext.trust

/**
 * Two-step confirmation inside a screen: the first press of a target arms it (the button asks "Wirklich …?"), a second
 * press of the **same** target within [timeoutMillis] confirms. Pressing another target re-arms that one instead;
 * an armed target falls back after the timeout. Times are passed in (pure, testable).
 */
class ArmedConfirmation<T : Any>(private val timeoutMillis: Long = 4_000) {
    private var target: T? = null
    private var armedAt = 0L

    /** The currently armed target, or `null` (also once the timeout passed). */
    fun armed(now: Long): T? {
        val t = target ?: return null
        if (now - armedAt > timeoutMillis) {
            target = null
            return null
        }
        return t
    }

    fun isArmed(candidate: T, now: Long): Boolean = armed(now) == candidate

    /** Presses [candidate]: `true` = confirmed (and disarmed), `false` = now armed and waiting for the second press. */
    fun press(candidate: T, now: Long): Boolean {
        if (isArmed(candidate, now)) {
            target = null
            return true
        }
        target = candidate
        armedAt = now
        return false
    }

    fun disarm() {
        target = null
    }
}
