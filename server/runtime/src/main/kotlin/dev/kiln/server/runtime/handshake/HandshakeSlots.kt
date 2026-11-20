package dev.kiln.server.runtime.handshake

/**
 * Concurrent configuration-phase handshakes per client address: every handshake holds a thread for up to the consent
 * timeout, so one address must not open arbitrarily many. `0` = unlimited. Thread-safe.
 */
class HandshakeSlots(private val maxPerAddress: Int) {
    private val active = HashMap<String, Int>()

    @Synchronized
    fun tryAcquire(address: String): Boolean {
        if (maxPerAddress <= 0) return true
        val count = active[address] ?: 0
        if (count >= maxPerAddress) return false
        active[address] = count + 1
        return true
    }

    @Synchronized
    fun release(address: String) {
        if (maxPerAddress <= 0) return
        val count = (active[address] ?: return) - 1
        if (count <= 0) active.remove(address) else active[address] = count
    }
}
