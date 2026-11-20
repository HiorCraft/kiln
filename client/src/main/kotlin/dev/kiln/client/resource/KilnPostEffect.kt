package dev.kiln.client.resource

import net.minecraft.resources.Identifier

/**
 * The post-processing effect the server switched on (`SetPostEffect`), added to the game's requested effects every
 * frame by `GameRendererMixin`. Unknown effects simply fail to load in the game (logged once by Minecraft).
 */
object KilnPostEffect {
    @Volatile
    var current: Identifier? = null
}
