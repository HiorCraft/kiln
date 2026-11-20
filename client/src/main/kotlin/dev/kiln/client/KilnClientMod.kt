package dev.kiln.client

import dev.kiln.client.creative.KilnCreativeTabs
import dev.kiln.client.entity.KilnEntityAppearances
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import dev.kiln.client.ext.handshake.ExtensionCache
import dev.kiln.client.ext.trust.TrustStore
import dev.kiln.client.keybind.KeybindStore
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.lua.sandbox.LuaSandbox
import dev.kiln.client.net.KilnPayload
import dev.kiln.protocol.CHANNEL
import dev.kiln.protocol.PROTOCOL_VERSION
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * Client entry point: registers the [CHANNEL] payload for the configuration and play phase, and hooks connection,
 * payload, tick and HUD events into [KilnClientImpl].
 */
object KilnClientMod : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("kiln")

    /** The runtime, consent store and package cache (set in [onInitializeClient]; used by the management screen). */
    lateinit var client: KilnClientImpl
        private set
    lateinit var trust: TrustStore
        private set
    lateinit var cache: ExtensionCache
        private set

    override fun onInitializeClient() {
        registerPayloadTypes()

        val loader = FabricLoader.getInstance()
        ServerKeybinds.init(KeybindStore(loader.configDir.resolve("kiln-keybinds.json")))
        trust = TrustStore(loader.configDir.resolve("kiln-trust.json")).also { it.load() }
        cache = ExtensionCache(loader.gameDir.resolve("kiln").resolve("cache"))
        LuaSandbox.protectStringMetatable()
        // placeholder creative tabs (the registry is frozen later); their content comes from the server
        KilnCreativeTabs.register()
        client = KilnClientImpl(cache, trust)

        registerConfigurationPhase()
        registerPlayPhase()
        // HUD layers of extensions: above the world overlays, below every vanilla HUD element
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath("kiln", "extensions"), client.extensions)

        logger.info("kiln client initialized (protocol {}, channel {})", PROTOCOL_VERSION, CHANNEL)
    }

    private fun registerPayloadTypes() {
        PayloadTypeRegistry.serverboundConfiguration().register(KilnPayload.TYPE, KilnPayload.CODEC)
        PayloadTypeRegistry.clientboundConfiguration().register(KilnPayload.TYPE, KilnPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(KilnPayload.TYPE, KilnPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(KilnPayload.TYPE, KilnPayload.CODEC)
    }

    /** Handshake and extension delivery (handlers run on the network thread). */
    private fun registerConfigurationPhase() {
        ClientConfigurationNetworking.registerGlobalReceiver(KilnPayload.TYPE) { payload, context ->
            context.client().execute { client.onConfigurationPayload(payload.data) }
        }
        ClientConfigurationConnectionEvents.START.register { listener, minecraft ->
            minecraft.execute { client.onConfigurationStart(listener) }
        }
        ClientConfigurationConnectionEvents.DISCONNECT.register { _, minecraft -> onMainThread(minecraft) { client.onDisconnect() } }
    }

    /** Play phase; the global receiver makes Fabric announce kiln:main via minecraft:register on join. */
    private fun registerPlayPhase() {
        ClientPlayNetworking.registerGlobalReceiver(KilnPayload.TYPE) { payload, context ->
            onMainThread(context.client()) { client.onPayload(payload.data) }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, minecraft -> onMainThread(minecraft) { client.onJoin() } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, minecraft -> onMainThread(minecraft) { client.onDisconnect() } }
        ClientTickEvents.END_CLIENT_TICK.register { client.tick() }
        // custom entities spawned after their EntityAppearance arrived: apply the announced hit box
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (KilnEntityAppearances.get(entity.id) != null) entity.refreshDimensions()
        }
    }

    private fun onMainThread(minecraft: Minecraft, action: () -> Unit) {
        if (minecraft.isSameThread) action() else minecraft.execute(action)
    }
}
