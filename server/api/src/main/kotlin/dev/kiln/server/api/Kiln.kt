package dev.kiln.server.api

import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionSource
import dev.kiln.server.api.item.CustomItems
import dev.kiln.server.api.enchantment.CustomEnchantments
import dev.kiln.server.api.keybind.Keybind
import dev.kiln.server.api.keybind.KeybindBuilder
import dev.kiln.server.api.keybind.KeyCode
import dev.kiln.server.api.screen.ScreenDefinition
import dev.kiln.server.api.screen.ScreenHandle
import dev.kiln.server.api.screen.Viewport
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * Entry point of the kiln server API.
 *
 * Obtain it with [Kiln.get] (the Kiln plugin registers it in Bukkit's `ServicesManager` on load).
 * Plugins using it declare `depend: [Kiln]` in their `plugin.yml`.
 *
 * Every player in the world runs the kiln client runtime with matching protocol version and has loaded all
 * registered extensions: everyone else is disconnected during the configuration phase. There are no fallbacks.
 *
 * All methods must be called on the server main thread; all handlers are invoked on the main thread.
 */
public interface Kiln {

    // ---- client -------------------------------------------------------------------------------------------

    /**
     * The player's GUI-scaled window size as last reported by the client; `null` until the client reported it
     * (shortly after join). Open screens are re-rendered automatically when it changes.
     */
    public fun viewport(player: Player): Viewport?

    // ---- screens ------------------------------------------------------------------------------------------

    /**
     * Renders [screen] and opens it for [player], replacing a kiln screen that is already open
     * (whose `onClose` then runs with [dev.kiln.server.api.screen.CloseReason.REPLACED]).
     *
     * @throws IllegalStateException if [player] is not online.
     */
    public fun openScreen(player: Player, screen: ScreenDefinition): ScreenHandle

    /** The kiln screen currently open for [player], if any. */
    public fun currentScreen(player: Player): ScreenHandle?

    // ---- notifications ------------------------------------------------------------------------------------

    /**
     * Shows a toast notification (vanilla toast stack, top right) with [title], optional [text] and optional [icon]
     * item (full stack, rendered like in an inventory), for [displayMillis] (1..60000). No-op for offline players.
     */
    public fun showToast(
        player: Player,
        title: Component,
        text: Component? = null,
        icon: ItemStack? = null,
        displayMillis: Int = 5_000,
    )

    // ---- keybinds -----------------------------------------------------------------------------------------

    /**
     * Declares a server keybind. Every client gets a key mapping in the category "Server: [category]"; the binding
     * the player chooses is stored on the client. Keybinds are removed automatically when [plugin] is disabled.
     * (Keybinds handled purely on the client are declared in Lua with `kiln.keys.bind`.)
     *
     * @param key unique id, usually `NamespacedKey(plugin, "open_menu")`.
     * @param name display name in the controls screen (plain text or a translation key).
     * @param defaultKey default binding, e.g. `KeyCode.keyboard("g")`.
     * @param category controls-screen category, defaults to the plugin name.
     */
    public fun registerKeybind(
        plugin: Plugin,
        key: Key,
        name: String,
        defaultKey: KeyCode = KeyCode.UNBOUND,
        category: String = plugin.name,
        handlers: KeybindBuilder.() -> Unit,
    ): Keybind

    /** All keybinds currently declared by any plugin. */
    public fun keybinds(): Collection<Keybind>

    // ---- Lua extensions -----------------------------------------------------------------------------------

    /**
     * Registers the Lua extension package [id] from [source]: validates it, builds the deterministic zip and from
     * now on delivers it to every connecting player. Call it in `onEnable`; players already online get it only
     * after reconnecting. Removed automatically when [plugin] is disabled.
     *
     * @param id must equal the `id` in `extension.json`.
     * @throws IllegalArgumentException if the package is invalid (message says why).
     * @throws IllegalStateException if an extension with this id is already registered.
     */
    public fun registerExtension(plugin: Plugin, id: String, source: ExtensionSource): Extension

    /** The registered extension [id], if any (also packages from `plugins/Kiln/extensions/`). */
    public fun extension(id: String): Extension?

    /** All registered extensions in delivery (load) order. */
    public fun extensions(): Collection<Extension>

    // ---- content ------------------------------------------------------------------------------------------

    /** Custom items backed by data components and extension resources. */
    public val items: CustomItems

    /**
     * Logic for (custom) enchantments; the enchantments themselves are registered in a bootstrapper with
     * [dev.kiln.server.api.enchantment.KilnBootstrap].
     */
    public val enchantments: CustomEnchantments

    public companion object {
        /** The registered implementation; throws if the Kiln plugin is not loaded. */
        @JvmStatic
        public fun get(): Kiln =
            Bukkit.getServicesManager().load(Kiln::class.java)
                ?: error("Kiln is not loaded. Add 'depend: [Kiln]' to your plugin.yml.")

        /** Convenience for keybind keys owned by [plugin]. */
        @JvmStatic
        public fun key(plugin: Plugin, value: String): NamespacedKey = NamespacedKey(plugin, value)
    }
}

/** Handle of a registration (listener, keybind, ...) that can be undone. */
public fun interface Registration {
    public fun unregister()
}
