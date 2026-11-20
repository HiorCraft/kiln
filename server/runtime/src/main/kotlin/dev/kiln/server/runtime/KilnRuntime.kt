package dev.kiln.server.runtime

import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.server.api.Kiln
import dev.kiln.server.api.enchantment.KilnBootstrap
import dev.kiln.server.runtime.enchantment.EnchantmentHooks
import dev.kiln.server.runtime.handshake.HandshakeManager
import dev.kiln.server.runtime.handshake.KickMessages
import dev.kiln.server.runtime.item.CustomItemListener
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Path
import java.util.logging.Logger

/**
 * The Kiln runtime, independent of who hosts it: the standalone [KilnPlugin] or another plugin that shades Kiln in
 * ([KilnEmbedded]). Everything is registered under [host] (service, plugin channel [CHANNEL], listeners, tasks) and
 * removed again by [disable] without touching anything else of the host.
 *
 * Lifecycle: construct in the host's `onLoad` (registers the [Kiln] service, so dependents can use it from their own
 * `onLoad`), [enable] in `onEnable`, [disable] in `onDisable`.
 */
class KilnRuntime(val host: Plugin, val logger: Logger) : Listener {
    @Volatile
    var settings: KilnSettings = KilnSettings()
        private set

    val bridge: KilnImpl = KilnImpl(host, logger) { settings }

    private var handshakes: HandshakeManager? = null
    private val itemListener = CustomItemListener(bridge.items)
    private val tasks = ArrayList<BukkitTask>()
    private val server get() = host.server

    @Volatile
    private var slots = settings.handshakeSlots()

    @Volatile
    private var downloads = settings.downloadBudget()

    init {
        server.servicesManager.register(Kiln::class.java, bridge, host, ServicePriority.Normal)
    }

    /** Starts serving clients with [settings]; Lua extension folders are read from [extensionsDir]. */
    fun enable(settings: KilnSettings, extensionsDir: Path) {
        this.settings = settings
        slots = settings.handshakeSlots()
        downloads = settings.downloadBudget()
        val handshakes = HandshakeManager(
            host, logger, bridge.extensionRegistry, { this.settings.timeouts }, { this.settings.sendThrottle }, { this.settings.debug },
            { slots }, { downloads },
        )
        this.handshakes = handshakes
        bridge.extensionRegistry.loadDirectory(host, extensionsDir)

        val messenger = server.messenger
        messenger.registerOutgoingPluginChannel(host, CHANNEL)
        messenger.registerIncomingPluginChannel(host, CHANNEL, object : PluginMessageListener {
            // play phase (joined players)
            override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
                bridge.receive(player, message)
            }

            // called for configuring and joined players; only the configuration phase is handled here
            override fun onPluginMessageReceived(channel: String, connection: PlayerConnection, message: ByteArray) {
                if (connection is PlayerConfigurationConnection) handshakes.receive(connection, message)
            }
        })
        val pm = server.pluginManager
        pm.registerEvents(this, host)
        pm.registerEvents(handshakes, host)
        pm.registerEvents(itemListener, host)
        pm.registerEvents(bridge.enchantments, host)
        pm.registerEvents(bridge.furniture, host)
        pm.registerEvents(bridge.entities, host)
        val scheduler = server.scheduler
        // fallback for clients whose channel registration did not produce a PlayerRegisterChannelEvent
        tasks += scheduler.runTaskTimer(host, Runnable { bridge.flushAll() }, 20L, 20L)
        tasks += scheduler.runTaskTimer(host, Runnable { handshakes.sweep() }, 20L * 60, 20L * 60)
        tasks += scheduler.runTaskTimer(host, Runnable { bridge.enchantments.tick() }, 20L, 20L)
        tasks += scheduler.runTaskTimer(host, Runnable { bridge.entities.tick() }, 10L, 10L)
        // dependencies can only be checked once every plugin had the chance to register its extensions
        scheduler.runTask(host, Runnable { bridge.extensionRegistry.logProblems() })

        // players that are online without a handshake (enabled at runtime) cannot be served
        server.onlinePlayers.forEach { it.kick(Component.text(KickMessages.NOT_HANDSHAKEN, NamedTextColor.RED)) }
        logger.info("Kiln enabled (protocol $PROTOCOL_VERSION, channel $CHANNEL, ${bridge.extensions().size} extension(s))")
        val throttle = settings.sendThrottle
        if (!throttle.unlimited) {
            val total = bridge.extensionRegistry.packages.sumOf { it.zip.size.toLong() }
            logger.info(
                "Extension download limited to $throttle per player: a full download of ${total / 1024} KiB takes " +
                    "~${(throttle.transferMillis(total) + 999) / 1000} s (download timeout extended accordingly)",
            )
        }
    }

    /** Stops Kiln and removes only what it registered under [host]. */
    fun disable() {
        handshakes?.shutdown()
        bridge.furniture.despawnAll()
        bridge.entities.storeAll()
        bridge.shutdown()
        tasks.forEach { it.cancel() }
        tasks.clear()
        HandlerList.unregisterAll(this)
        handshakes?.let(HandlerList::unregisterAll)
        HandlerList.unregisterAll(itemListener)
        HandlerList.unregisterAll(bridge.enchantments)
        HandlerList.unregisterAll(bridge.furniture)
        HandlerList.unregisterAll(bridge.entities)
        server.messenger.unregisterIncomingPluginChannel(host, CHANNEL)
        server.messenger.unregisterOutgoingPluginChannel(host, CHANNEL)
        server.servicesManager.unregister(Kiln::class.java, bridge)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (handshakes?.consumeReady(player.uniqueId) != true) {
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
        if (event.plugin !== host) bridge.onPluginDisable(event.plugin)
    }
}
