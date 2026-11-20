package dev.kiln.server.runtime.content

import net.kyori.adventure.key.Key

/** Checks of `Kiln.setPostEffect`: only effects from registered extensions (their `post_effect/` resources). */
object PostEffects {
    fun problem(effect: Key?, extensionIds: Set<String>): String? {
        if (effect == null) return null
        if (effect.namespace() !in extensionIds) {
            return "Post effect ${effect.asString()}: namespace '${effect.namespace()}' is no registered extension"
        }
        return null
    }
}
