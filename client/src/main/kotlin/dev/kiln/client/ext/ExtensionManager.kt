package dev.kiln.client.ext

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import dev.kiln.client.keybind.DynamicTranslations
import dev.kiln.client.keybind.KeybindHandler
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.lua.ExtensionHost
import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.PlayerSnapshot
import dev.kiln.client.lua.ViewportInfo
import dev.kiln.client.lua.WidgetInstance
import dev.kiln.client.lua.items.InventorySlot
import dev.kiln.client.lua.items.ItemRef
import dev.kiln.client.lua.sandbox.LuaLimits
import dev.kiln.client.lua.sandbox.LuaScriptException
import dev.kiln.client.lua.screen.LuaScreenSession
import dev.kiln.client.mixin.TextureManagerAccessor
import dev.kiln.client.screen.ItemStacks
import dev.kiln.client.screen.KilnScreen
import dev.kiln.client.toast.KilnToast
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Util
import org.slf4j.LoggerFactory

/**
 * The extensions of the current connection, on the Minecraft side: registers their textures (as [DynamicTexture]s
 * under `<ext>:textures/...`) and language entries, runs their Lua ([LuaExtension]), draws their HUD layers,
 * routes keybinds, channel messages and widget creation, and removes everything again on [unloadAll].
 *
 * [sender] delivers channel messages to the server (`false` outside the play phase).
 */
class ExtensionManager(
    private val sender: (channel: String, json: String) -> Boolean,
    /** `true` while Lua screens may be opened (play phase of a completed kiln connection). */
    private val inWorld: () -> Boolean = { true },
) : ExtensionHost, ExtensionTextures, HudElement {
    private val logger = LoggerFactory.getLogger("kiln")
    private val minecraft: Minecraft get() = Minecraft.getInstance()

    private val extensions = LinkedHashMap<String, LuaExtension>()
    private val textures = HashMap<String, IntArray>()
    private val translations = ArrayList<String>()

    private var serverKey = ""

    val loaded: Collection<LuaExtension> get() = extensions.values

    // ---- loading / unloading ------------------------------------------------------------------------------

    /** Starts a fresh set of extensions for [server] (address, key for keybind persistence). */
    fun beginLoad(server: String) {
        unloadAll()
        serverKey = server
        ServerKeybinds.setServer(server)
    }

    /**
     * Loads one package (textures, language entries, Lua `main`). Throws [ExtensionLoadException] with a readable
     * reason; the caller unloads everything then ([unloadAll]).
     */
    fun load(pkg: ExtensionPackage) {
        try {
            // the manifest was checked already (ExtensionHandshake); again here so no load path can skip it
            ExtensionNamespaces.problem(pkg.id, FabricLoader.getInstance()::isModLoaded)?.let { throw ExtensionPackageException(it) }
            registerTextures(pkg)
            registerTranslations(pkg)
            val extension = LuaExtension(pkg, this, LuaLimits())
            extensions[pkg.id] = extension
            extension.load()
            logger.info("Loaded extension {} {} ({})", pkg.id, pkg.version, pkg.permissions.joinToString())
        } catch (e: LuaScriptException) {
            throw ExtensionLoadException(e.message ?: "script error")
        } catch (e: ExtensionPackageException) {
            throw ExtensionLoadException(e.message ?: "invalid package")
        }
    }

    /** Removes every trace of the extensions (disconnect). */
    fun unloadAll() {
        closeLuaScreen { true }
        for (extension in extensions.values) {
            extension.stop()
            ServerKeybinds.removeExtension(extension.id)
        }
        extensions.clear()
        val textureManager = minecraft.textureManager
        for (id in textures.keys) Identifier.tryParse(id)?.let(textureManager::release)
        textures.clear()
        translations.forEach(DynamicTranslations::remove)
        translations.clear()
    }

    private fun registerTextures(pkg: ExtensionPackage) {
        for ((path, bytes) in pkg.files) {
            if (!path.startsWith("textures/") || !path.endsWith(".png")) continue
            val size = PngHeader.size(bytes) ?: throw ExtensionPackageException("${pkg.id}: $path is not a PNG")
            if (size[0] !in 1..MAX_TEXTURE_SIZE || size[1] !in 1..MAX_TEXTURE_SIZE) {
                throw ExtensionPackageException("${pkg.id}: $path is larger than ${MAX_TEXTURE_SIZE}x$MAX_TEXTURE_SIZE")
            }
            val image = try {
                NativeImage.read(bytes)
            } catch (e: Exception) {
                throw ExtensionPackageException("${pkg.id}: cannot decode $path")
            }
            val id = Identifier.fromNamespaceAndPath(pkg.id, path)
            ExtensionNamespaces.checkTexture(
                pkg.id, id.toString(),
                registered = { id.toString() in textures || (minecraft.textureManager as TextureManagerAccessor).`kiln$getByPath`().containsKey(id) },
                inResources = { minecraft.resourceManager.getResource(id).isPresent },
            )
            minecraft.textureManager.register(id, DynamicTexture({ "kiln extension $id" }, image))
            textures[id.toString()] = intArrayOf(image.width, image.height)
        }
    }

    /** `lang/en_us.json` and the current language; only keys in the extension's namespace (`<id>.…`). */
    private fun registerTranslations(pkg: ExtensionPackage) {
        val selected = minecraft.languageManager.selected
        val language = Language.getInstance()
        val own = HashSet<String>()
        for (locale in linkedSetOf("en_us", selected)) {
            val bytes = pkg.file("lang/$locale.json") ?: continue
            val json = try {
                JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                throw ExtensionPackageException("${pkg.id}: lang/$locale.json is not a JSON object")
            }
            var count = 0
            for ((key, value) in json.entrySet()) {
                if (!key.startsWith(pkg.id + ".") || !value.isJsonPrimitive || ++count > MAX_TRANSLATIONS_PER_FILE) continue
                ExtensionNamespaces.checkTranslation(pkg.id, key, own, language::has)
                DynamicTranslations.put(key, value.asString.take(MAX_TRANSLATION_LENGTH))
                own += key
                translations += key
            }
        }
    }

    // ---- runtime hooks ------------------------------------------------------------------------------------

    /** A widget instance for `Custom` type [type] (`<ext>:<name>`), or `null` (placeholder). */
    fun createWidget(type: Identifier): WidgetInstance? {
        val extension = extensions[type.namespace] ?: return null
        if (!extension.alive || !extension.hasWidget(type.path)) return null
        return extension.createWidget(type.path)
    }

    /** `ExtensionMessage` from the server. */
    fun onChannelMessage(channel: String, json: String) {
        val ns = channel.substringBefore(':')
        val name = channel.substringAfter(':')
        val extension = extensions[ns] ?: return logger.debug("No extension for channel {}", channel)
        extension.onChannelMessage(name, json)
    }

    // ---- HUD ----------------------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (extensions.isEmpty()) return
        val window = minecraft.window
        val screenW = window.guiScaledWidth
        val screenH = window.guiScaledHeight
        val target = GuiDrawTarget(graphics, minecraft.font, this, -1, -1)
        for (extension in extensions.values.toList()) {
            for (layer in extension.hudLayers.toList()) {
                if (!extension.alive) break
                val x = hudX(layer.anchor, layer.width, screenW)
                val y = hudY(layer.anchor, layer.height, screenH)
                graphics.enableScissor(x, y, x + layer.width, y + layer.height)
                try {
                    extension.renderHud(layer, target, x, y)
                } finally {
                    graphics.disableScissor()
                }
            }
        }
    }

    /** Left edge of a HUD layer: anchored left, centered or right (the default). */
    private fun hudX(anchor: String, width: Int, screenWidth: Int): Int = when (anchor) {
        "top_left", "left", "bottom_left" -> HUD_MARGIN
        "top", "center", "bottom" -> (screenWidth - width) / 2
        else -> screenWidth - width - HUD_MARGIN
    }

    /** Top edge of a HUD layer: anchored top, centered or bottom (the default). */
    private fun hudY(anchor: String, height: Int, screenHeight: Int): Int = when (anchor) {
        "top_left", "top", "top_right" -> HUD_MARGIN
        "left", "center", "right" -> (screenHeight - height) / 2
        else -> screenHeight - height - HUD_MARGIN
    }

    // ---- ExtensionHost ------------------------------------------------------------------------------------

    override fun send(channel: String, json: String): Boolean = sender(channel, json)

    override fun toast(extension: LuaExtension, title: String, text: String) {
        minecraft.gui.toastManager().addToast(KilnToast(Component.literal(title), Component.literal(text).takeIf { text.isNotEmpty() }, null, TOAST_MILLIS))
    }

    override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) {
        ServerKeybinds.registerExtension(extension.id, id, name, extension.name, defaultKey, KeybindHandler(extension::onKey))
    }

    override fun time(): Double {
        val level = minecraft.level ?: return 0.0
        return (level.gameTime + minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)) / 20.0
    }

    override fun player(): PlayerSnapshot? {
        val player = minecraft.player ?: return null
        return PlayerSnapshot(player.name.string, player.x, player.y, player.z)
    }

    override fun log(extension: LuaExtension, message: String) {
        logger.info("[{}] {}", extension.id, message)
    }

    override fun failed(extension: LuaExtension, error: LuaScriptException) {
        logger.warn("Extension {} stopped: {}", extension.id, error.message)
        detach(extension)
        // extensions importing it stop as well (no fallbacks)
        for (dependent in dependentsOf(extension.id)) {
            if (!dependent.alive) continue
            logger.warn("Extension {} stopped: its dependency {} was unloaded", dependent.id, extension.id)
            dependent.stop()
            detach(dependent)
        }
        minecraft.gui.toastManager().addToast(
            KilnToast(
                Component.literal("Erweiterung „${extension.name}“ entladen"),
                Component.literal(error.message ?: "Skriptfehler"),
                null,
                FAILURE_TOAST_MILLIS,
            ),
        )
    }

    /** Removes the keybinds and the open screen of a stopped [extension] (without running its Lua). */
    private fun detach(extension: LuaExtension) {
        ServerKeybinds.removeExtension(extension.id)
        closeLuaScreen { it.extension === extension }
    }

    /** Loaded extensions that (transitively) import [id]. */
    private fun dependentsOf(id: String): List<LuaExtension> {
        val result = LinkedHashSet<LuaExtension>()
        var changed = true
        while (changed) {
            changed = false
            for (e in extensions.values) {
                if (e !in result && e.dependencies.any { it == id || it in result.map(LuaExtension::id) }) {
                    result += e
                    changed = true
                }
            }
        }
        return result.toList()
    }

    // ---- Lua API v2 host functions ------------------------------------------------------------------------

    override fun extension(id: String): LuaExtension? = extensions[id]

    override fun openScreen(session: LuaScreenSession): Boolean {
        if (!inWorld() || minecraft.player == null) return false
        // never over the player's own screens (pause, chat, options, inventory): only over nothing or kiln screens
        val current = minecraft.gui.screen()
        if (current != null && current !is LuaScreen && current !is KilnScreen) return false
        minecraft.gui.setScreen(LuaScreen(session, this))
        return (minecraft.gui.screen() as? LuaScreen)?.session === session
    }

    override fun closeScreen(session: LuaScreenSession) {
        val screen = minecraft.gui.screen() as? LuaScreen ?: return
        if (screen.session === session) minecraft.gui.setScreen(null)
    }

    /** Closes the shown Lua screen without running Lua if [which] matches its session. */
    private fun closeLuaScreen(which: (LuaScreenSession) -> Boolean) {
        val screen = minecraft.gui.screen() as? LuaScreen ?: return
        if (!which(screen.session)) return
        screen.silent = true
        minecraft.gui.setScreen(null)
    }

    override fun decodeItem(nbt: ByteArray): ItemRef? = ItemStacks.decodeBytes(nbt)?.let(::StackItemRef)

    override fun inventory(): List<InventorySlot>? {
        val inventory = minecraft.player?.inventory ?: return null
        val result = ArrayList<InventorySlot>()
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            result += InventorySlot(slot, StackItemRef(stack.copyWithCount(1)), stack.count)
        }
        return result
    }

    override fun playSound(name: String, pitch: Float) {
        val sound = when (name) {
            "click" -> SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch)
            "pling" -> SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch)
            "success" -> SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch)
            "error" -> SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, pitch)
            "page" -> SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch)
            "toast" -> SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, pitch)
            else -> return
        }
        minecraft.soundManager.play(sound)
    }

    override fun playSound(id: String, volume: Float, pitch: Float) {
        val identifier = Identifier.tryParse(id) ?: return
        minecraft.soundManager.play(
            SimpleSoundInstance(identifier, SoundSource.UI, volume, pitch, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true),
        )
    }

    override fun clock(): Double = Util.getNanos() / 1e9

    override fun viewport(): ViewportInfo {
        val window = minecraft.window
        return ViewportInfo(window.guiScaledWidth, window.guiScaledHeight, window.guiScale.toDouble())
    }

    // ---- ExtensionTextures --------------------------------------------------------------------------------

    override fun size(id: String): IntArray? = textures[id]

    private companion object {
        const val HUD_MARGIN = 4
        const val MAX_TEXTURE_SIZE = 2048
        const val MAX_TRANSLATIONS_PER_FILE = 2_048
        const val MAX_TRANSLATION_LENGTH = 1_024
        const val TOAST_MILLIS = 5_000L
        const val FAILURE_TOAST_MILLIS = 10_000L
    }
}
