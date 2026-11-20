package dev.kiln.server.runtime.entity

/** Named cooldowns in ticks of a game clock. */
class Cooldowns {
    private val until = HashMap<String, Long>()

    fun ready(name: String, now: Long): Boolean = now >= (until[name] ?: Long.MIN_VALUE)

    fun start(name: String, ticks: Int, now: Long) {
        until[name] = now + ticks
    }
}
