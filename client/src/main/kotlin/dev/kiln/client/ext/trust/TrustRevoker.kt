package dev.kiln.client.ext.trust

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
