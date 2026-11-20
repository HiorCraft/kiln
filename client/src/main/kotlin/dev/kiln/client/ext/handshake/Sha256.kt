package dev.kiln.client.ext.handshake

import java.security.MessageDigest

/** SHA-256 helpers. */
object Sha256 {
    fun of(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
