package dev.kiln.server.runtime

import dev.kiln.server.api.screen.Viewport
import org.bukkit.entity.Player

/** State of a player in the world (always a kiln client that passed the handshake). Only touched on the player's thread. */
class PlayerSession(val player: Player) {
    /** Encoded messages waiting until the client has registered [dev.kiln.protocol.CHANNEL]. */
    val outbox = ArrayDeque<ByteArray>()
    var channelReady = false
    var screen: ScreenHandleImpl? = null
    private var nextScreenId = 1
    val pressedKeys = HashSet<String>()

    /** Last [dev.kiln.protocol.message.Viewport] the client reported, `null` until then. */
    var viewport: Viewport? = null

    fun allocateScreenId(): Int {
        val id = nextScreenId
        nextScreenId = if (nextScreenId == Int.MAX_VALUE) 1 else nextScreenId + 1
        return id
    }
}
