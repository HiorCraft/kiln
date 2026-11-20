package dev.kiln.client

import dev.kiln.client.creative.KilnCreativeTabs
import dev.kiln.client.entity.KilnEntityAppearances
import dev.kiln.client.entity.KilnEntityTraits
import dev.kiln.client.ext.handshake.ExtensionCache
import dev.kiln.client.ext.ExtensionIcons
import dev.kiln.client.ext.ExtensionManager
import dev.kiln.client.ext.handshake.HandshakePhase
import dev.kiln.client.ext.trust.TrustStore
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.mixin.ClientCommonPacketListenerAccessor
import dev.kiln.client.net.KilnPayload
import dev.kiln.client.resource.DeferredReload
import dev.kiln.client.resource.KilnPackSource
import dev.kiln.client.resource.KilnPostEffect
import dev.kiln.client.screen.Components
import dev.kiln.client.screen.ItemStacks
import dev.kiln.client.screen.KilnScreen
import dev.kiln.client.screen.ScreenHost
import dev.kiln.client.toast.KilnToast
import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.CloseScreen
import dev.kiln.protocol.message.CreativeTabs
import dev.kiln.protocol.message.DeclareKeybinds
import dev.kiln.protocol.message.EntityAppearance
import dev.kiln.protocol.message.EntityTraits
import dev.kiln.protocol.message.EntityTraitsRemove
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionMessage
import dev.kiln.protocol.message.Hello
import dev.kiln.protocol.message.OpenScreen
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.message.PlayAnimation
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.message.ServerboundMessage
import dev.kiln.protocol.message.SetPostEffect
import dev.kiln.protocol.message.ShowToast
import dev.kiln.protocol.message.StopAnimation
import dev.kiln.protocol.message.UpdateScreen
import dev.kiln.protocol.message.Viewport
import dev.kiln.protocol.message.Welcome
import dev.kiln.protocol.codec.MessageCodec
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.impl.networking.RegistrationPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * The client runtime. Everything runs on the client (render) thread; network handlers hop over with `execute`.
 *
 * Connection flow (protocol v3, see `docs/lua-extensions.md` "Entscheidungen"):
 * 1. Configuration phase starts: announce `kiln:main` (`minecraft:register`), send `Hello(3, [])`.
 * 2. `Welcome` + `ExtensionManifest` (with icons): consent first ([dev.kiln.client.ext.consent.ConsentScreen], skipped if this
 *    address already trusts the same ids+permissions); declined -> disconnect, nothing is downloaded.
 * 3. After consent: `ExtensionRequest` for the packages not in the cache, `ExtensionChunk`s reassembled and verified
 *    (size + SHA-256), stored in the cache; [dev.kiln.client.ext.LoadingScreen] shows the progress.
 * 4. Verify and start the extensions one per tick ([dev.kiln.client.ext.handshake.ExtensionHandshake], [ExtensionManager]);
 *    `ExtensionsReady(ok, error)`; the server kicks on `ok = false`.
 * 5. Play phase: screens, keybinds, toasts, channel messages; everything is removed on disconnect.
 */
class KilnClientImpl(
    private val cache: ExtensionCache,
    private val trust: TrustStore,
) : ScreenHost {
    private val logger = LoggerFactory.getLogger("kiln")

    /** Handshake done and all extensions loaded (`ExtensionsReady(true)` sent) for the current connection. */
    var isConnected: Boolean = false
        private set

    /** `true` between join (play phase) and disconnect. */
    private var inPlay = false

    /** In the world of a server with a completed kiln handshake (pause-menu entry point). */
    val isInKilnWorld: Boolean get() = isConnected && inPlay

    /** Trust key (address) of the server of the current kiln connection, or `null`. */
    val currentServer: String? get() = session?.server

    val extensions = ExtensionManager({ channel, json -> sendChannel(channel, json) }, { isInKilnWorld })

    /** Last viewport sent to the server (GUI-scaled window size). */
    private var sentViewport: Viewport? = null

    private var session: ConnectionSession? = null

    /**
     * A new handshake was set up during a reconfiguration while this session stays loaded until the backend answers.
     */
    private var switchCandidate: ConnectionSession? = null

    /** Icons of the announced extensions (consent and loading screens). */
    private val icons = ExtensionIcons()

    /** Reload without the extension pack after a disconnect (not inside it, see [DeferredReload]). */
    private val packReload = DeferredReload({
        val minecraft = Minecraft.getInstance()
        minecraft.level == null && minecraft.connection == null && minecraft.gui.overlay() == null
    }) { Minecraft.getInstance().reloadResourcePacks() }

    private fun newSession(listener: ClientConfigurationPacketListenerImpl, server: String) = ConnectionSession(
        listener, server, trust, cache, icons, extensions,
        sendConfiguration = ::sendConfiguration,
        isCurrent = { it === session },
    )

    // ---- sending ------------------------------------------------------------------------------------------

    /** Encodes and sends [message] in the play phase; `false` if not connected or the message is invalid. */
    override fun send(message: ServerboundMessage): Boolean {
        if (!isInKilnWorld) return false
        val bytes = encode(message) ?: return false
        return try {
            ClientPlayNetworking.send(KilnPayload(bytes))
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun sendConfiguration(message: ServerboundMessage): Boolean {
        val bytes = encode(message) ?: return false
        return try {
            ClientConfigurationNetworking.send(KilnPayload(bytes))
            true
        } catch (e: IllegalStateException) {
            logger.warn("Cannot send {} outside the configuration phase", message)
            false
        }
    }

    private fun encode(message: ServerboundMessage): ByteArray? = try {
        MessageCodec.encodeServerbound(message)
    } catch (e: ProtocolException) {
        logger.warn("Not sending invalid message {}: {}", message, e.message)
        null
    }

    private fun decode(data: ByteArray): ClientboundMessage? = try {
        MessageCodec.decodeClientbound(data)
    } catch (e: ProtocolException) {
        logger.warn("Dropping invalid kiln message ({} bytes): {}", data.size, e.message)
        null
    }

    private fun sendChannel(channel: String, json: String): Boolean {
        val id = ResourceId.tryParse(channel) ?: return false
        if (json.length > ProtocolLimits.MAX_EXTENSION_JSON_LENGTH) return false
        return send(ExtensionMessage(id, json))
    }

    // ---- configuration phase ------------------------------------------------------------------------------

    /** Configuration phase started (also after a server-initiated reconfiguration). */
    fun onConfigurationStart(listener: ClientConfigurationPacketListenerImpl) {
        val current = session
        if (isConnected && current != null) {
            // a proxy may switch the backend here: its entity ids and creative content are not ours any more
            clearBackendContent()
            // announce again: a new backend answers with Welcome (new handshake, see onConfigurationPayload); the
            // same backend stays silent and this session goes on. Consent stays keyed by the proxy's address.
            logger.info("Reconfiguration: announcing kiln again (proxy backend switch?)")
            switchCandidate = newSession(listener, current.server)
            announce()
            return
        }
        resetConnection()
        val serverData = (listener as ClientCommonPacketListenerAccessor).`kiln$getServerData`()
        session = newSession(listener, serverKey(serverData?.ip, Minecraft.getInstance().isLocalServer))
        announce()
    }

    /** The new backend answered: drop the old backend's extensions and continue with [next] (resources stay until it decides). */
    private fun switchSession(next: ConnectionSession) {
        logger.info("New kiln backend: unloading {} extension(s) of the previous one", session?.handshake?.items?.size ?: 0)
        unloadSessionContent()
        session = next
        resetPlayState()
    }

    /** `minecraft:register` for [KilnPayload] and the kiln Hello. */
    private fun announce() {
        // Paper only reads plugin messages on channels the client announced; Fabric announces its configuration
        // channels only in reply to a server-side minecraft:register, which Paper does not send in this phase.
        try {
            ClientConfigurationNetworking.send(RegistrationPayload(RegistrationPayload.REGISTER, listOf(KilnPayload.TYPE.id())))
        } catch (e: RuntimeException) {
            logger.warn("Could not announce {}", KilnPayload.TYPE.id(), e)
        }
        sendConfiguration(Hello(PROTOCOL_VERSION, emptyList()))
    }

    fun onConfigurationPayload(data: ByteArray) {
        val message = decode(data) ?: return
        if (message is Welcome) switchCandidate?.let { next ->
            switchCandidate = null
            switchSession(next)
        }
        val s = session ?: return
        val handshake = s.handshake
        when (message) {
            is Welcome -> handshake.onWelcome(message.protocolVersion)
            is ExtensionManifest -> {
                s.manifestAt = Util.getMillis()
                logger.info("Server announced {} extension(s)", message.extensions.size)
                handshake.onManifest(message)
            }
            is ExtensionChunk -> handshake.onChunk(message)
            else -> logger.warn("Unexpected {} in the configuration phase", message)
        }
        afterHandshakeStep(s)
    }

    /** Called every client tick: advances loading one step (so the loading screen shows each state). */
    private fun tickHandshake() {
        val s = session ?: return
        s.handshake.tick()
        afterHandshakeStep(s)
    }

    private fun afterHandshakeStep(s: ConnectionSession) {
        if (s.reported) return
        when (s.handshake.phase) {
            HandshakePhase.DONE -> {
                s.reported = true
                isConnected = true
                logger.info("kiln ready: {} extension(s) loaded", s.handshake.items.size)
            }
            HandshakePhase.FAILED -> {
                s.reported = true
                logger.warn("Extensions failed: {}", s.handshake.error)
                extensions.unloadAll()
            }
            else -> {}
        }
    }

    // ---- play phase ---------------------------------------------------------------------------------------

    fun onJoin() {
        // reconfiguration ended without a Welcome: same backend, the old session goes on
        switchCandidate = null
        inPlay = true
        sentViewport = null
        if (isConnected) sendViewportIfChanged()
    }

    fun onDisconnect() {
        resetConnection()
    }

    /**
     * The player revoked the consent of the server this client is connected to: unload every extension and leave the
     * server with a clear message (there are no fallbacks; the next join asks for consent again).
     */
    fun disconnectAfterRevoke(server: String) {
        logger.info("Consent for {} revoked while connected: unloading extensions and disconnecting", server)
        extensions.unloadAll()
        ServerKeybinds.clear()
        val reason = Component.literal("Zustimmung zurückgenommen\n\nDu hast die Zustimmung zu den Erweiterungen von $server zurückgenommen. " +
            "Beim nächsten Verbinden wirst du erneut gefragt.")
        val connection = Minecraft.getInstance().connection?.getConnection() ?: session?.connection
        if (connection == null) {
            resetConnection()
            return
        }
        connection.disconnect(reason)
        // handled right away (not only by the next game tick, which does not run while a local world is paused)
        if (!connection.isConnected) connection.handleDisconnection()
    }

    private fun resetConnection() {
        unloadSessionContent()
        clearBackendContent()
        // the extensions' resource pack goes with them, reloaded once the disconnect is complete
        if (KilnPackSource.clear()) packReload.request()
        session = null
        switchCandidate = null
        resetPlayState()
    }

    /** Removes what the session's extensions and server put up: open screen, keybinds, extensions, post effect, icons. */
    private fun unloadSessionContent() {
        openKilnScreen()?.closeSilently = true
        ServerKeybinds.clear()
        extensions.unloadAll()
        KilnPostEffect.clear()
        icons.clear()
    }

    /** Removes the content tied to the backend server: entity traits/appearances (by entity id) and creative tabs. */
    private fun clearBackendContent() {
        KilnEntityTraits.CLIENT.clear()
        KilnEntityAppearances.clear()
        KilnCreativeTabs.clear()
    }

    private fun resetPlayState() {
        isConnected = false
        inPlay = false
        sentViewport = null
    }

    fun onPayload(data: ByteArray) {
        if (!inPlay) return
        val message = decode(data) ?: return
        try {
            handle(message)
        } catch (e: Exception) {
            logger.error("Failed to handle kiln message {}", message, e)
        }
    }

    private fun handle(message: ClientboundMessage) {
        if (!isConnected) {
            logger.debug("Ignoring {} without a completed handshake", message)
            return
        }
        val minecraft = Minecraft.getInstance()
        when (message) {
            is DeclareKeybinds -> ServerKeybinds.declare(session?.server ?: serverKey(minecraft.currentServer?.ip, minecraft.isLocalServer), message.keybinds)
            is OpenScreen -> {
                openKilnScreen()?.closeSilently = true
                minecraft.gui.setScreen(KilnScreen(message.screenId, message.root, this, message.fullscreen))
            }
            is UpdateScreen -> {
                val screen = openKilnScreen(message.screenId)
                if (screen != null) screen.update(message.root, message.fullscreen)
                else logger.debug("Ignoring update for screen {} (not open)", message.screenId)
            }
            // removed() acknowledges with ScreenClosed
            is CloseScreen -> if (openKilnScreen(message.screenId) != null) minecraft.gui.setScreen(null)
            is ExtensionMessage -> extensions.onChannelMessage(message.channel.toString(), message.json)
            is ShowToast -> showToast(minecraft, message)
            is SetPostEffect -> KilnPostEffect.request(message.id?.let(Identifier::tryParse)) { id ->
                val shaders = minecraft.shaderManager
                shaders.isPostEffectValid(id, LevelTargetBundle.MAIN_TARGETS) && shaders.getPostChain(id, LevelTargetBundle.MAIN_TARGETS) != null
            }
            is CreativeTabs -> KilnCreativeTabs.apply(message.reset, message.tabs)
            is EntityTraits -> KilnEntityTraits.CLIENT.set(message.entityId, message.solid)
            is EntityTraitsRemove -> {
                KilnEntityTraits.CLIENT.remove(message.entityIds)
                KilnEntityAppearances.remove(message.entityIds)
            }
            is EntityAppearance -> KilnEntityAppearances.set(message)
            is PlayAnimation -> KilnEntityAppearances.play(message.entityId, message.name, message.mode, message.speed, Util.getMillis() / 1000.0)
            is StopAnimation -> KilnEntityAppearances.stop(message.entityId, message.name)
            is Welcome, is ExtensionManifest, is ExtensionChunk -> logger.debug("Ignoring {} in the play phase", message)
        }
    }

    /** The open [KilnScreen], if any (and only if it shows [screenId] when given). */
    private fun openKilnScreen(screenId: Int? = null): KilnScreen? =
        (Minecraft.getInstance().gui.screen() as? KilnScreen)?.takeIf { screenId == null || it.screenId == screenId }

    private fun showToast(minecraft: Minecraft, message: ShowToast) {
        val title = Components.parse(message.title) ?: Component.empty()
        val text = Components.parse(message.text)
        val icon = message.icon?.let { ItemStacks.decode(it) }
        val millis = message.displayMillis.coerceIn(MIN_TOAST_MILLIS, ProtocolLimits.MAX_TOAST_MILLIS).toLong()
        minecraft.gui.toastManager().addToast(KilnToast(title, text, icon, millis))
    }

    // ---- ScreenHost ---------------------------------------------------------------------------------------

    override fun createWidget(type: Identifier) = extensions.createWidget(type)

    override val textures get() = extensions

    // ---- ticking ------------------------------------------------------------------------------------------

    /** Reports the GUI-scaled window size whenever it changes (resize, GUI scale, fullscreen toggle). */
    private fun sendViewportIfChanged() {
        if (!isInKilnWorld) return
        val window = Minecraft.getInstance().window
        val viewport = Viewport(window.guiScaledWidth.coerceIn(1, ProtocolLimits.MAX_SIZE), window.guiScaledHeight.coerceIn(1, ProtocolLimits.MAX_SIZE))
        if (viewport == sentViewport) return
        if (send(viewport)) sentViewport = viewport
    }

    fun tick() {
        packReload.tick()
        tickHandshake()
        sendViewportIfChanged()
        ServerKeybinds.tick(isInKilnWorld) { send(it) }
    }

    companion object {
        /** Shortest display time of a server toast. */
        private const val MIN_TOAST_MILLIS = 500

        /**
         * Key for consent and keybind persistence: the address as entered in the server list (lowercase). In the
         * configuration phase it comes from the listener's `ServerData`, because `Minecraft.getCurrentServer()` is
         * only set once the play phase starts.
         */
        fun serverKey(address: String?, localServer: Boolean): String =
            address?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: if (localServer) "singleplayer" else "unknown"
    }
}
