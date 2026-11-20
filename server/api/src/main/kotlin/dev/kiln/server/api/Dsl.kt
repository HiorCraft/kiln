package dev.kiln.server.api

import dev.kiln.server.api.extension.Extension
import dev.kiln.server.api.extension.ExtensionSource
import dev.kiln.server.api.screen.ScreenBuilder
import dev.kiln.server.api.screen.ScreenDefinition
import dev.kiln.server.api.screen.ScreenHandle
import dev.kiln.server.api.screen.Viewport
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Scopes the kiln DSL builders so outer receivers are not accidentally used. */
@DslMarker
public annotation class KilnDsl

// ---- Player extensions ----------------------------------------------------------------------------------------

/** See [Kiln.openScreen]. */
public fun Player.openScreen(screen: ScreenDefinition): ScreenHandle = Kiln.get().openScreen(this, screen)

/** Opens an ad-hoc screen; shorthand for `openScreen(screen(width, height, content))`. */
public fun Player.openScreen(width: Int, height: Int, content: ScreenBuilder.() -> Unit): ScreenHandle =
    openScreen(ScreenDefinition(width, height, false, content))

/** Opens an ad-hoc fullscreen screen; shorthand for `openScreen(screen(fullscreen = true, content))`. */
public fun Player.openFullscreen(content: ScreenBuilder.() -> Unit): ScreenHandle =
    openScreen(ScreenDefinition(0, 0, true, content))

/** See [Kiln.viewport]. */
public val Player.kilnViewport: Viewport? get() = Kiln.get().viewport(this)

/** See [Kiln.showToast]. */
public fun Player.showToast(title: Component, text: Component? = null, icon: ItemStack? = null, displayMillis: Int = 5_000): Unit =
    Kiln.get().showToast(this, title, text, icon, displayMillis)

/** See [Kiln.currentScreen]. */
public val Player.currentKilnScreen: ScreenHandle? get() = Kiln.get().currentScreen(this)

// ---- Plugin extensions ----------------------------------------------------------------------------------------

/**
 * Registers the Lua extension [id] from this plugin's jar resources at [resourcePath]
 * (default `extensions/<id>`, i.e. `src/main/resources/extensions/<id>/extension.json`).
 * See [Kiln.registerExtension].
 */
public fun Plugin.registerExtension(id: String, resourcePath: String = "extensions/$id"): Extension =
    Kiln.get().registerExtension(this, id, ExtensionSource.PluginResource(this, resourcePath))
