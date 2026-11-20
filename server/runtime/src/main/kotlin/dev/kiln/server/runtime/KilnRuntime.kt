package dev.kiln.server.runtime

import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.server.api.Kiln
import dev.kiln.server.api.enchantment.KilnBootstrap
import dev.kiln.server.runtime.enchantment.EnchantmentHookRegistry
import dev.kiln.server.runtime.enchantment.EnchantmentHooks
import dev.kiln.server.runtime.entity.CustomEntityManager
import dev.kiln.server.runtime.handshake.HandshakeManager
import dev.kiln.server.runtime.handshake.KickMessages
import dev.kiln.server.runtime.handshake.SendThrottle
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
        listeners().forEach { server.pluginManager.registerEvents(it, host) }
        startTasks(handshakes)

        // players that are online without a handshake (enabled at runtime) cannot be served
        server.onlinePlayers.forEach(::kickNotHandshaken)
        logger.info("Kiln enabled (protocol $PROTOCOL_VERSION, channel $CHANNEL, ${bridge.extensions().size} extension(s))")
        logThrottle(settings.sendThrottle)
    }

    private fun listeners(): List<Listener> =
        listOfNotNull(this, handshakes, itemListener, bridge.enchantments, bridge.furniture, bridge.entities)

    private fun startTasks(handshakes: HandshakeManager) {
        val scheduler = server.scheduler
        fun every(ticks: Long, task: () -> Unit) {
            tasks += scheduler.runTaskTimer(host, Runnable(task), ticks, ticks)
        }
        // fallback for clients whose channel registration did not produce a PlayerRegisterChannelEvent
        every(TICKS_PER_SECOND) { bridge.flushAll() }
        every(TICKS_PER_MINUTE) { handshakes.sweep() }
        every(EnchantmentHookRegistry.TICK_INTERVAL.toLong()) { bridge.enchantments.tick() }
        every(CustomEntityManager.TICK_INTERVAL.toLong()) { bridge.entities.tick() }
        // dependencies can only be checked once every plugin had the chance to register its extensions
        scheduler.runTask(host, Runnable { bridge.extensionRegistry.logProblems() })
    }

    private fun logThrottle(throttle: SendThrottle) {
        if (throttle.unlimited) return
        val total = bridge.extensionRegistry.packages.sumOf { it.zip.size.toLong() }
        logger.info(
            "Extension download limited to $throttle per player: a full download of ${total / 1024} KiB takes " +
                "~${(throttle.transferMillis(total) + 999) / 1000} s (download timeout extended accordingly)",
        )
    }

    private fun kickNotHandshaken(player: Player) = player.kick(Component.text(KickMessages.NOT_HANDSHAKEN, NamedTextColor.RED))

    /** Stops Kiln and removes only what it registered under [host]. */
    fun disable() {
        handshakes?.shutdown()
        bridge.furniture.despawnAll()
        bridge.entities.storeAll()
        bridge.shutdown()
        tasks.forEach { it.cancel() }
        tasks.clear()
        listeners().forEach(HandlerList::unregisterAll)
        server.messenger.unregisterIncomingPluginChannel(host, CHANNEL)
        server.messenger.unregisterOutgoingPluginChannel(host, CHANNEL)
        server.servicesManager.unregister(Kiln::class.java, bridge)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (handshakes?.consumeReady(player.uniqueId) != true) {
            logger.info("Disconnecting ${player.name}: joined without kiln handshake")
            kickNotHandshaken(player)
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

    companion object {
        private const val TICKS_PER_SECOND = 20L
        private const val TICKS_PER_MINUTE = TICKS_PER_SECOND * 60

        /** The plugin other than [host] that already provides the Kiln service, or `null`. */
        fun otherProvider(host: Plugin): Plugin? =
            host.server.servicesManager.getRegistration(Kiln::class.java)?.plugin?.takeIf { it !== host }

        /** Why Kiln cannot run on this server (Minecraft version, Kotlin runtime); empty if it can. */
        fun problems(host: Plugin): List<String> =
            RuntimeChecks.problems(BuildInfo.load(), host.server.minecraftVersion, KotlinVersion.CURRENT)
    }
}
