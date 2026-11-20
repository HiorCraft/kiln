package dev.kiln.client.screen

/**
 * Client/server synchronisation state of one text field, free of Minecraft types so it can be unit-tested.
 *
 * - Local edits are reported throttled ([THROTTLE_MS]): at most one `ScreenInput` per interval, the latest value
 *   always wins, and [poll] with `force = true` (enter, screen close) sends immediately.
 * - A value from the server is applied only if it is a real change by the server: it differs from the last value the
 *   server sent ([serverValue]), from the last value the client reported ([reportedValue]) and from the local content.
 *   Echoes of the client's own input or unchanged values in a re-rendered tree therefore never overwrite what the
 *   player is typing (and never move the cursor).
 */
class TextFieldSync(initialServerValue: String) {
    /** Last value received from the server for this field. */
    var serverValue: String = initialServerValue
        private set

    /** Last value sent to the server (initially the server's own value: nothing to report). */
    var reportedValue: String = initialServerValue
        private set

    /** Local value not yet reported, or `null`. */
    var pending: String? = null
        private set

    private var lastSentAt: Long = Long.MIN_VALUE / 2

    /** Called on every local edit. */
    fun onLocalEdit(value: String) {
        pending = value
    }

    /**
     * Returns the value to send now (and marks it as reported), or `null` if nothing is due.
     * @param force send a pending value regardless of the throttle interval (enter, close)
     */
    fun poll(now: Long, force: Boolean = false): String? {
        val value = pending ?: return null
        if (value == reportedValue) {
            pending = null
            return null
        }
        if (!force && now - lastSentAt < THROTTLE_MS) return null
        pending = null
        reportedValue = value
        lastSentAt = now
        return value
    }

    /**
     * The server sent [incoming] for this field (in an `UpdateScreen`); [localValue] is the current content.
     * @return `true` if the field content must be replaced by [incoming]
     */
    fun onServerValue(incoming: String, localValue: String): Boolean {
        val changedByServer = incoming != serverValue && incoming != reportedValue
        serverValue = incoming
        if (incoming == localValue) {
            // already in sync: nothing to report any more
            reportedValue = incoming
            if (pending == incoming) pending = null
            return false
        }
        if (!changedByServer) return false
        reportedValue = incoming
        pending = null
        return true
    }

    companion object {
        const val THROTTLE_MS: Long = 150
    }
}
