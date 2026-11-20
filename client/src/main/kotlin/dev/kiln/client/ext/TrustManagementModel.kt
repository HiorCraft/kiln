package dev.kiln.client.ext

import dev.kiln.protocol.ExtensionPermissions

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

/** What a revoke button acts on. */
sealed interface RevokeTarget {
    data class Server(val address: String) : RevokeTarget
    data object All : RevokeTarget
    data object Cache : RevokeTarget
}

/** One server of the management list: address and the stored grants (sorted by id). */
data class TrustEntry(val server: String, val grants: List<TrustStore.Grant>) {
    companion object {
        /** Entries of [servers], sorted by address. */
        fun of(servers: Map<String, Set<TrustStore.Grant>>): List<TrustEntry> =
            servers.entries.sortedBy { it.key }.map { (server, grants) -> TrustEntry(server, grants.sortedBy { it.id }) }

        /** Height of an extension block in the expanded details: id line, every known permission, spacing. */
        val GRANT_HEIGHT: Int = 13 + 12 * ExtensionPermissions.ALL.size + 5

        fun detailHeight(grants: Int): Int = 4 + grants * GRANT_HEIGHT
    }
}

/**
 * Revoking consent: removes the stored entries (saved immediately by [TrustStore]) and, if the server the client is
 * connected to right now lost its consent, calls [onCurrentRevoked] (unload the extensions and disconnect; there are
 * no fallbacks). The next join of that server asks again.
 */
class TrustRevoker(
    private val trust: TrustStore,
    /** Trust key of the server the client is connected to (kiln session), or `null`. */
    private val currentServer: () -> String?,
    private val onCurrentRevoked: (server: String) -> Unit,
) {
    /** Revokes [server]; `true` if an entry existed. */
    fun revoke(server: String): Boolean {
        val removed = trust.remove(server)
        if (removed && currentServer() == server) onCurrentRevoked(server)
        return removed
    }

    /** Revokes every server; returns the removed addresses. */
    fun revokeAll(): Set<String> {
        val removed = trust.removeAll()
        val current = currentServer()
        if (current != null && current in removed) onCurrentRevoked(current)
        return removed
    }
}
