package dev.kiln.server.handshake

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent
import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.server.extension.ExtensionRegistry
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Paper glue of the configuration-phase handshake: collects the messages of configuring clients, runs
 * [ConfigurationHandshake] inside [AsyncPlayerConnectionConfigureEvent] (which holds the player in the
 * configuration phase until it returns) and disconnects on failure. Players who passed are remembered until they
 * join ([consumeReady]).
 */
class HandshakeManager(
    private val plugin: Plugin,
    private val logger: Logger,
    private val registry: ExtensionRegistry,
    private val timeouts: () -> HandshakeTimeouts,
    private val throttle: () -> SendThrottle,
    private val debug: () -> Boolean,
) : Listener {
    private val pending = ConcurrentHashMap<UUID, HandshakeInbox>()
    private val ready = ConcurrentHashMap<UUID, Long>()

    @Volatile
    private var shuttingDown = false

    /** Plugin message from a client in the configuration phase (any thread). */
    fun receive(connection: PlayerConfigurationConnection, bytes: ByteArray) {
        val uuid = connection.profile.id ?: return
        if (pending.size >= MAX_PENDING && !pending.containsKey(uuid)) {
            logger.warning("Too many configuring kiln clients; dropping message of ${connection.profile.name}")
            return
        }
        pending.computeIfAbsent(uuid) { HandshakeInbox() }.offerBytes(bytes)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onConfigure(event: AsyncPlayerConnectionConfigureEvent) {
        val connection = event.connection
        val profile = connection.profile
        val uuid = profile.id ?: return
        val name = profile.name ?: uuid.toString()
        // reconfiguration of a player already in the world: the extensions are still loaded on the client
        if (Bukkit.getPlayer(uuid) != null) return
        if (shuttingDown) {
            connection.disconnect(Component.text(KickMessages.SHUTDOWN))
            return
        }
        val inbox = pending.computeIfAbsent(uuid) { HandshakeInbox() }
        val link = object : HandshakeLink {
            override fun send(message: ClientboundMessage) {
                connection.sendPluginMessage(plugin, CHANNEL, MessageCodec.encodeClientbound(message))
            }

            override fun isOpen(): Boolean = connection.isConnected && !shuttingDown

            override fun channelRegistered(): Boolean = CHANNEL in connection.listeningPluginChannels
        }
        val started = System.currentTimeMillis()
        val result = try {
            val delivery = registry.delivery
            ConfigurationHandshake(link, inbox, delivery.packages, timeouts(), throttle(), dependencyProblems = delivery.problems).run()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            logger.log(Level.SEVERE, "kiln handshake of $name failed", t)
            HandshakeResult.Kick(KickMessages.INTERNAL_ERROR, "internal error: $t")
        } finally {
            pending.remove(uuid, inbox)
        }
        when (result) {
            HandshakeResult.Success -> {
                ready[uuid] = System.currentTimeMillis()
                if (debug()) logger.info("[debug] $name passed the kiln handshake in ${System.currentTimeMillis() - started} ms")
            }
            HandshakeResult.Closed -> if (debug()) logger.info("[debug] $name disconnected during the kiln handshake")
            is HandshakeResult.Kick -> {
                logger.info("Disconnecting $name: ${result.log}")
                if (connection.isConnected) connection.disconnect(Component.text(result.reason, NamedTextColor.RED))
            }
        }
    }

    /** `true` (once) if [uuid] completed the handshake; called on join. */
    fun consumeReady(uuid: UUID): Boolean = ready.remove(uuid) != null

    @EventHandler(priority = EventPriority.MONITOR)
    fun onConnectionClose(event: PlayerConnectionCloseEvent) {
        pending.remove(event.playerUniqueId)
    }

    /** Drops stale state of clients that vanished without a close event. */
    fun sweep() {
        val now = System.currentTimeMillis()
        pending.values.removeIf { now - it.createdAt > STALE_MILLIS }
        ready.values.removeIf { now - it > STALE_MILLIS }
    }

    fun shutdown() {
        shuttingDown = true
        pending.clear()
        ready.clear()
    }

    private companion object {
        const val MAX_PENDING = 1_024
        const val STALE_MILLIS = 5 * 60_000L
    }
}
