package dev.kiln.client.ext.trust

/** The player's consent to extension [id] with exactly these [permissions]. */
data class TrustGrant(val id: String, val permissions: Set<String>)
