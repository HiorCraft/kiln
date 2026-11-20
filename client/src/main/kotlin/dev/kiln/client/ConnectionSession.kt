package dev.kiln.client

import dev.kiln.client.entity.KilnModels
import dev.kiln.client.ext.consent.ConsentScreen
import dev.kiln.client.ext.handshake.ExtensionCache
import dev.kiln.client.ext.handshake.ExtensionHandshake
import dev.kiln.client.ext.ExtensionIcons
import dev.kiln.client.ext.ExtensionManager
import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.ext.handshake.HandshakeIo
import dev.kiln.client.ext.LoadingScreen
import dev.kiln.client.ext.trust.TrustGrant
import dev.kiln.client.ext.trust.TrustStore
import dev.kiln.client.mixin.ClientCommonPacketListenerAccessor
import dev.kiln.client.resource.KilnPackSource
import dev.kiln.client.resource.KilnResourceReload
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.message.ServerboundMessage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackType
import org.slf4j.LoggerFactory

/**
 * One connection's configuration-phase state: the [handshake] with server [server] (trust key) and what it needs from
 * the game. Callbacks that arrive after the session was replaced ([isCurrent] `false`) are dropped.
 */
internal class ConnectionSession(
    val listener: ClientConfigurationPacketListenerImpl,
    val server: String,
    private val trustStore: TrustStore,
    private val cache: ExtensionCache,
    private val icons: ExtensionIcons,
    private val extensions: ExtensionManager,
    private val sendConfiguration: (ServerboundMessage) -> Boolean,
    private val isCurrent: (ConnectionSession) -> Boolean,
) : HandshakeIo {
    private val logger = LoggerFactory.getLogger("kiln")

    val handshake = ExtensionHandshake(this, minProgressMillis = MIN_PROGRESS_MILLIS, minFinishMillis = MIN_FINISH_MILLIS)

    /** When the manifest arrived (the server's consent timeout runs from there). */
    var manifestAt = 0L

    /** The handshake's outcome (done or failed) was handled. */
    var reported = false

    val connection: Connection get() = (listener as ClientCommonPacketListenerAccessor).`kiln$getConnection`()

    override fun send(message: ServerboundMessage) {
        sendConfiguration(message)
    }

    override fun isTrusted(grants: List<TrustGrant>) = trustStore.isTrusted(server, grants)

    override fun trust(grants: List<TrustGrant>) {
        trustStore.trust(server, grants)
        trustStore.save()
    }

    override fun cached(info: ExtensionInfo): ByteArray? = cache.get(info)

    override fun store(info: ExtensionInfo, zip: ByteArray) = cache.put(info, zip)

    override fun askConsent(infos: List<ExtensionInfo>) {
        logger.info("Asking for consent to {} extension(s) of {}", infos.size, server)
        icons.prepare(infos)
        Minecraft.getInstance().gui.setScreen(ConsentScreen(server, infos, icons, manifestAt + CONSENT_TIMEOUT_MILLIS,
            onAccept = {
                logger.info("Extensions of {} accepted", server)
                if (isCurrent(this)) handshake.consent(true)
            },
            onDeny = {
                logger.info("Extensions of {} declined, disconnecting", server)
                if (isCurrent(this)) handshake.consent(false)
            },
            tickConnection = ::tickConnection,
        ))
    }

    override fun showProgress() {
        icons.prepare(handshake.items.map { it.info })
        Minecraft.getInstance().gui.setScreen(LoadingScreen(server, handshake, icons, ::tickConnection))
    }

    override fun beginLoad() = extensions.beginLoad(server)

    override fun load(pkg: ExtensionPackage) = extensions.load(pkg)

    override fun unloadAll() = extensions.unloadAll()

    override fun isModLoaded(id: String) = FabricLoader.getInstance().isModLoaded(id)

    override fun isResourceNamespace(id: String): Boolean =
        Minecraft.getInstance().resourceManager.listPacks().anyMatch { pack ->
            pack.packId() != KilnPackSource.PACK_ID && id in pack.getNamespaces(PackType.CLIENT_RESOURCES)
        }

    override fun resourcesMounted(): Boolean = KilnPackSource.isMounted()

    override fun screenCovered(): Boolean = Minecraft.getInstance().gui.overlay() != null

    override fun reloadResources(assets: Map<String, ByteArray>, done: (error: String?) -> Unit) {
        // backend switch to a server with the same resources: keep them (a reload takes seconds)
        if (KilnPackSource.matches(assets)) {
            logger.info("Extension resources unchanged; no reload")
            done(null)
            return
        }
        val minecraft = Minecraft.getInstance()
        KilnPackSource.set(assets)
        logger.info("Reloading resources with {} extension asset file(s)", assets.size)
        // failures do not complete the future; MinecraftReloadMixin reports them through KilnResourceReload
        KilnResourceReload.CLIENT.begin { error -> if (isCurrent(this)) done(error) }
        minecraft.reloadResourcePacks().whenComplete { _, error ->
            minecraft.execute {
                KilnModels.clear() // entity models come from the new resources
                if (error != null) KilnResourceReload.CLIENT.failed(error) { KilnPackSource.clear() } else KilnResourceReload.CLIENT.completed()
            }
        }
    }

    override fun disconnect(reason: String) {
        connection.disconnect(Component.literal(reason))
    }

    /**
     * Our screens replace the `ConnectScreen`, which is what ticks the pending connection in the configuration
     * phase; without this a server-side disconnect (timeout, kick) would go unnoticed and the screen would stay.
     */
    fun tickConnection() {
        if (Minecraft.getInstance().isLocalServer) return // Minecraft ticks its pending local connection itself
        val connection = connection
        if (connection.isConnected) connection.tick() else connection.handleDisconnection()
    }

    private companion object {
        /** Server-side limit for manifest -> `ExtensionRequest`, i.e. the consent screen (`docs/lua-extensions.md`). */
        const val CONSENT_TIMEOUT_MILLIS = 300_000L

        /** The loading screen stays this long before a resource reload covers it (see ExtensionHandshake). */
        const val MIN_PROGRESS_MILLIS = 1_500L

        /** The finished loading screen (100 %) stays this long before the client reports ready. */
        const val MIN_FINISH_MILLIS = 700L
    }
}
