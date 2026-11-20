package dev.kiln.server.runtime

import dev.kiln.server.api.Kiln
import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.server.runtime.handshake.HandshakeManager
import dev.kiln.server.runtime.item.CustomItemListener
import dev.kiln.server.runtime.enchantment.EnchantmentHooks
import dev.kiln.server.api.enchantment.KilnBootstrap
import org.bukkit.event.server.ServerLoadEvent
import dev.kiln.server.runtime.handshake.HandshakeTimeouts
import dev.kiln.server.runtime.handshake.KickMessages
import dev.kiln.server.runtime.handshake.SendThrottle
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * The Kiln runtime plugin: registers the [Kiln] service on load; on enable the [CHANNEL] plugin
 * channel, the configuration-phase handshake (Lua extension delivery), listeners and the outbox flush task.
 */
class KilnPlugin : JavaPlugin(), Listener {
    lateinit var bridge: KilnImpl
        private set

    private lateinit var handshakes: HandshakeManager

    /** `debug: true` in config.yml logs every dropped client message. */
    var debug: Boolean = false
        private set

    /** Max client messages per player and second; the excess is dropped. */
    var rateLimitPerSecond: Int = 100
        private set

    /** Max [dev.kiln.protocol.ExtensionMessage]s per player and second; the excess is dropped. */
    var extensionMessagesPerSecond: Int = 40
        private set

    @Volatile
    private var timeouts = HandshakeTimeouts()

    /** `extension-send-rate-kib-per-second`: per-player bandwidth limit for extension chunks (0 = unlimited). */
    @Volatile
    private var sendThrottle = SendThrottle.UNLIMITED

    override fun onLoad() {
        bridge = KilnImpl(this)
        server.servicesManager.register(Kiln::class.java, bridge, this, ServicePriority.Normal)
    }

    override fun onEnable() {
        saveDefaultConfig()
        debug = config.getBoolean("debug", false)
        rateLimitPerSecond = config.getInt("rate-limit-per-second", 100).coerceAtLeast(1)
        extensionMessagesPerSecond = config.getInt("extension-messages-per-second", 40).coerceAtLeast(1)
        timeouts = HandshakeTimeouts(
            helloMillis = config.getLong("hello-timeout-seconds", 10).coerceIn(1, 120) * 1_000,
            consentMillis = config.getLong("consent-timeout-seconds", 300).coerceIn(10, 3_600) * 1_000,
            extensionsMillis = config.getLong("extensions-timeout-seconds", 60).coerceIn(5, 600) * 1_000,
            resourceReloadMillis = config.getLong("resource-reload-timeout-seconds", 120).coerceIn(10, 900) * 1_000,
        )

        sendThrottle = SendThrottle.kibPerSecond(config.getLong("extension-send-rate-kib-per-second", 0))

        handshakes = HandshakeManager(this, logger, bridge.extensionRegistry, { timeouts }, { sendThrottle }, { debug })
        bridge.extensionRegistry.loadDirectory(this, dataFolder.toPath().resolve("extensions"))

        val messenger = server.messenger
        messenger.registerOutgoingPluginChannel(this, CHANNEL)
        messenger.registerIncomingPluginChannel(this, CHANNEL, object : PluginMessageListener {
            // play phase (joined players)
            override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
                bridge.receive(player, message)
            }

            // called for configuring and joined players; only the configuration phase is handled here
            override fun onPluginMessageReceived(channel: String, connection: PlayerConnection, message: ByteArray) {
                if (connection is PlayerConfigurationConnection) handshakes.receive(connection, message)
            }
        })
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(handshakes, this)
        server.pluginManager.registerEvents(CustomItemListener(bridge.items), this)
        server.pluginManager.registerEvents(bridge.enchantments, this)
        server.pluginManager.registerEvents(bridge.furniture, this)
        server.pluginManager.registerEvents(bridge.entities, this)
        // fallback for clients whose channel registration did not produce a PlayerRegisterChannelEvent
        server.scheduler.runTaskTimer(this, Runnable { bridge.flushAll() }, 20L, 20L)
        server.scheduler.runTaskTimer(this, Runnable { handshakes.sweep() }, 20L * 60, 20L * 60)
        server.scheduler.runTaskTimer(this, Runnable { bridge.enchantments.tick() }, 20L, 20L)
        server.scheduler.runTaskTimer(this, Runnable { bridge.entities.tick() }, 10L, 10L)
        // dependencies can only be checked once every plugin had the chance to register its extensions
        server.scheduler.runTask(this, Runnable { bridge.extensionRegistry.logProblems() })

        // players that are online without a handshake (plugin enabled at runtime) cannot be served
        server.onlinePlayers.forEach { it.kick(Component.text(KickMessages.NOT_HANDSHAKEN, NamedTextColor.RED)) }
        logger.info("Kiln enabled (protocol $PROTOCOL_VERSION, channel $CHANNEL, ${bridge.extensions().size} extension(s))")
        if (!sendThrottle.unlimited) {
            val total = bridge.extensionRegistry.packages.sumOf { it.zip.size.toLong() }
            logger.info(
                "Extension download limited to $sendThrottle per player: a full download of ${total / 1024} KiB takes " +
                    "~${(sendThrottle.transferMillis(total) + 999) / 1000} s (download timeout extended accordingly)",
            )
        }
    }

    override fun onDisable() {
        if (::handshakes.isInitialized) handshakes.shutdown()
        bridge.furniture.despawnAll()
        bridge.entities.storeAll()
        bridge.shutdown()
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
        server.servicesManager.unregisterAll(this)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!handshakes.consumeReady(player.uniqueId)) {
            logger.info("Disconnecting ${player.name}: joined without kiln handshake")
            player.kick(Component.text(KickMessages.NOT_HANDSHAKEN, NamedTextColor.RED))
            return
        }
        bridge.onJoin(player)
    }

    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel != CHANNEL) return
        bridge.flushPlayer(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        bridge.onQuit(event.player)
    }

    /** After every plugin enabled: bootstrap enchantments without an extension of the same namespace. */
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        val ids = bridge.extensions().map { it.id }.toSet()
        EnchantmentHooks.warnings(KilnBootstrap.registered, ids).forEach(logger::warning)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== this) bridge.onPluginDisable(event.plugin)
    }
}
