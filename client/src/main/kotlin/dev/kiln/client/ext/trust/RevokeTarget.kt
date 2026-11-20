package dev.kiln.client.ext.trust

/** What a revoke button acts on. */
sealed interface RevokeTarget {
    data class Server(val address: String) : RevokeTarget
    data object All : RevokeTarget
    data object Cache : RevokeTarget
}
