package dev.kiln.server.runtime

import dev.kiln.server.api.Kiln
import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionSource
import dev.kiln.server.api.keybind.KeyCode
import dev.kiln.server.api.keybind.Keybind
import dev.kiln.server.api.keybind.KeybindBuilder
import dev.kiln.server.api.screen.ClickContext
import dev.kiln.server.api.screen.CloseContext
import dev.kiln.server.api.screen.CloseReason
import dev.kiln.server.api.screen.InputContext
import dev.kiln.server.api.screen.ModalDismissContext
import dev.kiln.server.api.screen.MouseButton
import dev.kiln.server.api.screen.ScreenDefinition
import dev.kiln.server.api.screen.ScreenHandle
import dev.kiln.server.api.screen.Viewport
import dev.kiln.protocol.ClickModifiers
import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.CloseScreen
import dev.kiln.protocol.DeclareKeybinds
import dev.kiln.protocol.ExtensionMessage
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.Hello
import dev.kiln.protocol.KeyPressed
import dev.kiln.protocol.KeyReleased
import dev.kiln.protocol.ModalDismiss
import dev.kiln.protocol.OpenScreen
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.ScreenClick
import dev.kiln.protocol.ScreenClosed
import dev.kiln.protocol.ScreenInput
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.ShowToast
import dev.kiln.protocol.codec.MessageCodec
import dev.kiln.server.runtime.extension.ExtensionMessenger
import dev.kiln.server.runtime.extension.ExtensionRegistry
import dev.kiln.server.runtime.keybind.KeybindImpl
import dev.kiln.server.runtime.keybind.KeybindRegistry
import dev.kiln.server.runtime.screen.EventValidator
import dev.kiln.server.runtime.screen.ScreenConverter
import dev.kiln.server.runtime.screen.Validation
import com.google.gson.JsonParseException
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Implementation of [Kiln]. All state is mutated on the main thread (Paper delivers plugin messages there;
 * should it not, messages are re-dispatched via the player's entity scheduler).
 */
class KilnImpl(private val plugin: KilnPlugin) : Kiln, ExtensionMessenger {
    private val sessions = HashMap<UUID, PlayerSession>()
    private val rateLimiters = ConcurrentHashMap<UUID, RateLimiter>()
    private val extensionRateLimiters = HashMap<UUID, RateLimiter>()
    private var keybindsDirty = false

    val extensionRegistry = ExtensionRegistry(plugin.logger, this)

    val keybindRegistry = KeybindRegistry(
        pressedLookup = { player, id -> sessions[player.uniqueId]?.pressedKeys?.contains(id) == true },
        onChanged = ::scheduleKeybindBroadcast,
    )

    private val logger get() = plugin.logger

    fun debug(message: () -> String) {
        if (plugin.debug) logger.info("[debug] ${message()}")
    }

    // ---- sessions -----------------------------------------------------------------------------------------

    private fun session(player: Player): PlayerSession? = sessions[player.uniqueId]?.takeIf { it.player === player }

    private fun requireSession(player: Player): PlayerSession =
        session(player) ?: throw IllegalStateException("${player.name} is not online (no kiln session)")

    /**
     * Called on join for a player that passed the configuration-phase handshake: creates the session and declares
     * the server keybinds (sent as soon as the client registered the channel in the play phase).
     */
    fun onJoin(player: Player) {
        val session = PlayerSession(player)
        sessions[player.uniqueId] = session
        send(session, DeclareKeybinds(keybindRegistry.declarations()))
        debug { "${player.name} joined with kiln session" }
    }

    override fun viewport(player: Player): Viewport? = session(player)?.viewport

    // ---- notifications ------------------------------------------------------------------------------------

    override fun showToast(player: Player, title: Component, text: Component?, icon: ItemStack?, displayMillis: Int) {
        require(displayMillis in 1..60_000) { "displayMillis out of range: $displayMillis" }
        val session = session(player) ?: return
        val iconData = icon?.takeUnless { it.isEmpty }?.let { ScreenConverter.stack(it, it.amount) }
        send(session, ShowToast(ScreenConverter.json(title), text?.let(ScreenConverter::json), iconData, displayMillis))
    }

    // ---- screens ------------------------------------------------------------------------------------------

    override fun openScreen(player: Player, screen: ScreenDefinition): ScreenHandle {
        val session = requireSession(player)
        val handle = ScreenHandleImpl(this, session, session.allocateScreenId(), screen)
        val (converted, onClose) = handle.render() // throws before any state changes
        handle.apply(converted, onClose)
        val previous = session.screen
        handle.isOpen = true
        session.screen = handle
        send(session, OpenScreen(handle.id, converted.root, converted.fullscreen)) // replaces the previous screen on the client
        if (previous != null) closeScreen(previous, CloseReason.REPLACED, notifyClient = false)
        return handle
    }

    override fun currentScreen(player: Player): ScreenHandle? = session(player)?.screen

    fun closeScreen(handle: ScreenHandleImpl, reason: CloseReason, notifyClient: Boolean) {
        if (!handle.isOpen) return
        handle.isOpen = false
        val session = handle.session
        if (session.screen === handle) session.screen = null
        if (notifyClient && sessions[session.player.uniqueId] === session) send(session, CloseScreen(handle.id))
        val handler = handle.closeHandler ?: return
        runHandler("close handler of $handle") { handler(CloseContext(session.player, handle, reason)) }
    }

    // ---- keybinds -----------------------------------------------------------------------------------------

    override fun registerKeybind(
        plugin: Plugin,
        key: Key,
        name: String,
        defaultKey: KeyCode,
        category: String,
        handlers: KeybindBuilder.() -> Unit,
    ): Keybind = keybindRegistry.register(plugin, key, name, defaultKey, category, KeybindBuilder().apply(handlers).build())

    override fun keybinds(): Collection<Keybind> = keybindRegistry.all()

    private fun scheduleKeybindBroadcast() {
        if (keybindsDirty || sessions.isEmpty() || !plugin.isEnabled) return
        keybindsDirty = true
        // coalesce several (un)registrations in the same tick into one DeclareKeybinds
        Bukkit.getScheduler().runTask(plugin, Runnable {
            keybindsDirty = false
            val message = DeclareKeybinds(keybindRegistry.declarations())
            sessions.values.forEach { send(it, message) }
        })
    }

    // ---- Lua extensions -----------------------------------------------------------------------------------

    override fun registerExtension(plugin: Plugin, id: String, source: ExtensionSource): Extension {
        require(plugin.isEnabled || plugin === this.plugin) { "${plugin.name} is not enabled" }
        return extensionRegistry.register(plugin, id, source)
    }

    override fun extension(id: String): Extension? = extensionRegistry.get(id)

    override fun extensions(): Collection<Extension> = extensionRegistry.all()

    override fun sendExtensionMessage(player: Player, channel: ResourceId, json: String) {
        val session = session(player) ?: return
        send(session, ExtensionMessage(channel, json))
    }

    // ---- sending ------------------------------------------------------------------------------------------

    /**
     * Encodes and sends [message]. Paper silently drops custom payloads on channels the client has not registered
     * (`minecraft:register`), so messages are queued until the channel shows up in `listeningPluginChannels`.
     * @throws IllegalArgumentException if the message violates a protocol limit.
     */
    fun send(session: PlayerSession, message: ClientboundMessage) {
        val bytes = try {
            MessageCodec.encodeClientbound(message)
        } catch (e: ProtocolException) {
            throw IllegalArgumentException("Cannot send ${message::class.simpleName}: ${e.message}", e)
        }
        session.outbox.addLast(bytes)
        if (session.outbox.size > MAX_QUEUED) {
            session.outbox.removeFirst()
            logger.warning("Dropping queued message for ${session.player.name}: channel $CHANNEL still not registered")
        }
        flush(session)
    }

    fun flush(session: PlayerSession) {
        if (session.outbox.isEmpty()) return
        val player = session.player
        if (!player.isOnline) return
        if (!session.channelReady) {
            if (CHANNEL !in player.listeningPluginChannels) return
            session.channelReady = true
        }
        while (session.outbox.isNotEmpty()) player.sendPluginMessage(plugin, CHANNEL, session.outbox.removeFirst())
    }

    fun flushPlayer(player: Player) {
        session(player)?.let(::flush)
    }

    fun flushAll() {
        sessions.values.forEach(::flush)
    }

    // ---- receiving ----------------------------------------------------------------------------------------

    /** Entry point from the plugin messenger: rate limit, decode, then handle on the player's thread. */
    fun receive(player: Player, bytes: ByteArray) {
        val limiter = rateLimiters.computeIfAbsent(player.uniqueId) { RateLimiter(plugin.rateLimitPerSecond) }
        val accepted = synchronized(limiter) { limiter.tryAcquire() }
        if (!accepted) {
            if (limiter.rejectedInWindow == 1) logger.warning("${player.name} exceeds ${plugin.rateLimitPerSecond} kiln messages/s; dropping")
            return
        }
        val message = try {
            MessageCodec.decodeServerbound(bytes)
        } catch (e: ProtocolException) {
            debug { "Dropping malformed message from ${player.name}: ${e.message}" }
            return
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            handle(player, message)
        } else {
            player.scheduler.run(plugin, { handle(player, message) }, null)
        }
    }

    private fun handle(player: Player, message: ServerboundMessage) {
        if (!player.isOnline) return
        val session = session(player)
        if (session == null) {
            debug { "Dropping ${message::class.simpleName} from ${player.name}: not connected" }
            return
        }
        when (message) {
            is ScreenClick -> onClick(session, message)
            is ScreenInput -> onInput(session, message)
            is ScreenClosed -> onClosed(session, message)
            is ModalDismiss -> onModalDismiss(session, message)
            is dev.kiln.protocol.Viewport -> onViewport(session, message)
            is KeyPressed -> onKey(session, message.keyId, pressed = true)
            is KeyReleased -> onKey(session, message.keyId, pressed = false)
            is ExtensionMessage -> onExtension(session, message)
            // configuration-phase messages are meaningless in the play phase
            is Hello, is ExtensionRequest, is ExtensionsReady ->
                debug { "Dropping ${message::class.simpleName} from ${session.player.name} in the play phase" }
        }
    }

    private fun onClick(session: PlayerSession, event: ScreenClick) {
        val screen = session.screen
        val result = EventValidator.validateClick(event, screen?.id, screen?.tree)
        if (result is Validation.Invalid) {
            debug { "Dropping click from ${session.player.name}: ${result.reason}" }
            return
        }
        val handler = screen!!.tree!!.clickHandlers[event.elementId] ?: return
        val button = when (event.button) {
            0 -> MouseButton.LEFT
            1 -> MouseButton.RIGHT
            2 -> MouseButton.MIDDLE
            else -> MouseButton.OTHER
        }
        val mods = event.modifiers
        runHandler("click handler '${event.elementId}' of $screen") {
            handler(
                ClickContext(
                    session.player, screen, event.elementId, button,
                    shift = mods and ClickModifiers.SHIFT != 0,
                    control = mods and ClickModifiers.CONTROL != 0,
                    alt = mods and ClickModifiers.ALT != 0,
                ),
            )
        }
    }

    private fun onInput(session: PlayerSession, event: ScreenInput) {
        val screen = session.screen
        val result = EventValidator.validateInput(event, screen?.id, screen?.tree)
        if (result is Validation.Invalid) {
            debug { "Dropping input from ${session.player.name}: ${result.reason}" }
            return
        }
        screen!!.clientInput(event.elementId, event.value)
        val handler = screen.tree!!.inputHandlers[event.elementId] ?: return
        runHandler("input handler '${event.elementId}' of $screen") {
            handler(InputContext(session.player, screen, event.elementId, event.value))
        }
    }

    private fun onModalDismiss(session: PlayerSession, event: ModalDismiss) {
        val screen = session.screen
        val result = EventValidator.validateModalDismiss(event, screen?.id, screen?.tree)
        if (result is Validation.Invalid) {
            debug { "Dropping modal dismiss from ${session.player.name}: ${result.reason}" }
            return
        }
        val handler = screen!!.tree!!.dismissHandlers[event.modalId] ?: return
        runHandler("dismiss handler of modal '${event.modalId}' of $screen") {
            handler(ModalDismissContext(session.player, screen, event.modalId))
        }
    }

    /** Stores the new viewport and re-renders the open screen (sends an update only if the tree changed). */
    private fun onViewport(session: PlayerSession, event: dev.kiln.protocol.Viewport) {
        val viewport = Viewport(event.width, event.height)
        if (session.viewport == viewport) return
        session.viewport = viewport
        val screen = session.screen ?: return
        runHandler("re-render of $screen for viewport ${viewport.width}x${viewport.height}") { screen.refresh() }
    }

    private fun onClosed(session: PlayerSession, event: ScreenClosed) {
        val screen = session.screen
        val result = EventValidator.validateClosed(event.screenId, screen?.id)
        if (result is Validation.Invalid) {
            // normal after a server close or a replacement (the client acknowledges the old screen)
            debug { "Ignoring ScreenClosed from ${session.player.name}: ${result.reason}" }
            return
        }
        closeScreen(screen!!, CloseReason.PLAYER, notifyClient = false)
    }

    private fun onKey(session: PlayerSession, keyId: String, pressed: Boolean) {
        val keybind = keybindRegistry.byWireId(keyId)
        if (keybind == null) {
            debug { "Dropping key event for unknown keybind '$keyId' from ${session.player.name}" }
            return
        }
        val handler = if (pressed) {
            session.pressedKeys += keyId
            keybind.handlers.onPress
        } else {
            session.pressedKeys -= keyId
            keybind.handlers.onRelease
        } ?: return
        runHandler("${if (pressed) "press" else "release"} handler of $keybind") { handler(session.player) }
    }

    private fun onExtension(session: PlayerSession, message: ExtensionMessage) {
        val player = session.player
        val limiter = extensionRateLimiters.getOrPut(player.uniqueId) { RateLimiter(plugin.extensionMessagesPerSecond) }
        if (!limiter.tryAcquire()) {
            if (limiter.rejectedInWindow == 1) {
                logger.warning("${player.name} exceeds ${plugin.extensionMessagesPerSecond} extension messages/s; dropping")
            }
            return
        }
        val channel = extensionRegistry.channel(message.channel)
        if (channel == null) {
            debug { "No handler for extension channel ${message.channel} (from ${player.name})" }
            return
        }
        runHandler("handler of extension channel ${message.channel}") {
            try {
                channel.receive(player, message.json)
            } catch (e: JsonParseException) {
                debug { "Dropping invalid JSON on ${message.channel} from ${player.name}: ${e.message}" }
            }
        }
    }

    private inline fun runHandler(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            logger.log(Level.SEVERE, "Exception in kiln $what", t)
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    fun onQuit(player: Player) {
        rateLimiters.remove(player.uniqueId)
        extensionRateLimiters.remove(player.uniqueId)
        val session = sessions[player.uniqueId]?.takeIf { it.player === player } ?: return
        sessions.remove(player.uniqueId)
        session.outbox.clear()
        session.pressedKeys.clear()
        session.screen?.let { closeScreen(it, CloseReason.DISCONNECT, notifyClient = false) }
    }

    /** Removes everything [disabled] registered and closes the screens its code defined. */
    fun onPluginDisable(disabled: Plugin) {
        keybindRegistry.removeAll(disabled)
        extensionRegistry.removeAll(disabled)
        val loader = disabled.javaClass.classLoader
        sessions.values.mapNotNull { it.screen }
            .filter { it.definition.content.javaClass.classLoader === loader }
            .forEach { closeScreen(it, CloseReason.SERVER, notifyClient = true) }
    }

    /** Called from onDisable; the plugin is already marked disabled, so nothing can be sent anymore. */
    fun shutdown() {
        sessions.values.toList().forEach { session ->
            session.screen?.let { closeScreen(it, CloseReason.SERVER, notifyClient = false) }
        }
        sessions.clear()
        rateLimiters.clear()
        extensionRateLimiters.clear()
        extensionRegistry.clear()
    }

    private companion object {
        const val MAX_QUEUED = 256
    }
}
