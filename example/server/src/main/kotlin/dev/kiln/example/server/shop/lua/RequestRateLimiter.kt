package dev.kiln.example.server.shop.lua

import java.util.UUID

/** Token bucket per player: [capacity] requests at once, refilled with [perSecond]. Pure (time is passed in). */
class RequestRateLimiter(private val capacity: Double = 6.0, private val perSecond: Double = 4.0) {
    private val buckets = HashMap<UUID, TokenBucket>()

    fun tryAcquire(player: UUID, nowMillis: Long): Boolean {
        val bucket = buckets.getOrPut(player) { TokenBucket(capacity, nowMillis) }
        val elapsed = (nowMillis - bucket.at).coerceAtLeast(0)
        bucket.tokens = minOf(capacity, bucket.tokens + elapsed * perSecond / 1000.0)
        bucket.at = nowMillis
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    fun forget(player: UUID) {
        buckets.remove(player)
    }
}
