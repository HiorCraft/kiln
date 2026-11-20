package dev.kiln.server.api.extension

import dev.kiln.server.api.Registration
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player

/** A typed channel of an [Extension], see [Extension.channel]. */
public interface ExtensionChannel<T : Any> : Registration {
    public val extension: Extension

    /** `<extension id>:<name>` */
    public val key: Key

    /**
     * Sends [message] (serialized with Gson) to the extension on [player]'s client. Does nothing if the player is
     * offline. @throws IllegalArgumentException if the JSON exceeds 262144 chars (messages from the client are
     * limited to 16384 chars). Items go in as strings from [ExtensionItems.encode].
     */
    public fun send(player: Player, message: T)

    /** Sends [message] to every online player. */
    public fun broadcast(message: T)
}
