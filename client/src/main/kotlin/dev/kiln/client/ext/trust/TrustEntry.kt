package dev.kiln.client.ext.trust

import dev.kiln.protocol.ExtensionPermissions

/** One server of the management list: address and the stored grants (sorted by id). */
data class TrustEntry(val server: String, val grants: List<TrustGrant>) {
    companion object {
        /** Entries of [servers], sorted by address. */
        fun of(servers: Map<String, Set<TrustGrant>>): List<TrustEntry> =
            servers.entries.sortedBy { it.key }.map { (server, grants) -> TrustEntry(server, grants.sortedBy { it.id }) }

        /** Height of an extension block in the expanded details: id line, every known permission, spacing. */
        val GRANT_HEIGHT: Int = 13 + 12 * ExtensionPermissions.ALL.size + 5

        fun detailHeight(grants: Int): Int = 4 + grants * GRANT_HEIGHT
    }
}
