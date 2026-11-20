package dev.kiln.server.api.extension

import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * A registered Lua extension package. Every player in the world has downloaded, accepted and loaded it during the
 * configuration phase (players who could not are disconnected), so there is no "has extension" check.
 *
 * Players already online when an extension is registered do not get it until they reconnect.
 */
public interface Extension {
    /** `extension.json` id, `[a-z0-9_]{1,32}`; namespace of the extension's widgets, channels and textures. */
    public val id: String
    public val version: String
    public val name: String

    /**
     * Granted permissions (subset of `widgets`, `keybinds`, `hud`, `channels`, `toasts`, `screens`, `inventory`,
     * `resources`).
     */
    public val permissions: Set<String>

    /**
     * Ids of the extensions this one imports (`extension.json` `dependencies`). They are delivered and loaded before
     * it; while a dependency is missing, has a non-matching version or forms a cycle, joining players are
     * disconnected (see `docs/lua-extensions.md`, "Lua-API v2").
     */
    public val dependencies: List<String>

    /** Plugin that registered the extension; the Kiln plugin for packages from `plugins/Kiln/extensions/`. */
    public val plugin: Plugin

    /** SHA-256 of the delivered (deterministic) package zip, 64 hex chars. */
    public val sha256: String

    /** Size of the delivered package zip in bytes. */
    public val size: Int

    /**
     * Typed channel `<id>:<name>` to the extension's Lua code (`kiln.net.channel(name)`). Messages are
     * JSON, (de)serialized with Gson as [type]. [handler] (main thread) receives every valid message from a
     * client; invalid JSON, oversized or rate-limited messages are dropped before. Client input is untrusted:
     * fields may be missing (`null` despite Kotlin non-null types) and must be validated.
     *
     * One channel object per name: registering the same name again replaces the previous handler.
     *
     * @param name `[a-z0-9_]{1,32}`
     * @param handler `null` for a send-only channel.
     */
    public fun <T : Any> channel(
        name: String,
        type: Class<T>,
        handler: (ExtensionChannel<T>.(player: Player, message: T) -> Unit)? = null,
    ): ExtensionChannel<T>

    /** `<id>:<name>`: key of content (items, sounds, fonts, models, …) in this extension's namespace. */
    public fun key(name: String): Key = Key.key(id, name)

    /** Widget type `<id>:<name>` for `custom(id, extension.widget(name), ...)`, registered in Lua via `kiln.ui.widget`. */
    public fun widget(name: String): Key

    /** Removes the extension; players that connect afterwards no longer get it. Channels stop receiving. */
    public fun unregister()
}

/** Typed [Extension.channel] with a receive handler. */
public inline fun <reified T : Any> Extension.channel(
    name: String,
    noinline handler: ExtensionChannel<T>.(player: Player, message: T) -> Unit,
): ExtensionChannel<T> = channel(name, T::class.java, handler)

/** Typed send-only [Extension.channel]. */
public inline fun <reified T : Any> Extension.channel(name: String): ExtensionChannel<T> = channel(name, T::class.java, null)
