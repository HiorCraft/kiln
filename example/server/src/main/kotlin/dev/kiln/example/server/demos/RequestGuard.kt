package dev.kiln.example.server.demos

import com.google.gson.JsonObject
import dev.kiln.example.server.shop.lua.RequestFields
import dev.kiln.example.server.shop.lua.RequestRateLimiter
import java.util.UUID

/** Rate limit (token bucket like the shop: 6 at once, then 4/s per player) and request id for every request. */
class RequestGuard(private val limiter: RequestRateLimiter = RequestRateLimiter()) {
    fun run(player: UUID, msg: JsonObject?, action: String, now: Long, body: (req: Long) -> DemoResult): DemoResult {
        val req = RequestFields.req(msg)
        if (!limiter.tryAcquire(player, now)) return DemoResult.fail(req, action, DemoResult.TOO_MANY)
        return body(req)
    }

    fun forget(player: UUID) = limiter.forget(player)
}
