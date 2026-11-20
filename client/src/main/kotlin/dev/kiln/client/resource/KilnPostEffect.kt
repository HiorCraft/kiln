package dev.kiln.client.resource

import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * The post-processing effect the server switched on (`SetPostEffect`), added to the game's requested effects every
 * frame by `GameRendererMixin`.
 *
 * Minecraft treats a post chain that fails to load as a broken resource pack: it disables the server's packs and
 * disconnects. So an effect is loaded once while [probing] (`ShaderManagerMixin` then skips that recovery) and only
 * requested if it loaded; a broken effect is logged and ignored.
 */
object KilnPostEffect {
    private val logger = LoggerFactory.getLogger("kiln")

    @Volatile
    var current: Identifier? = null
        private set

    /** `true` while [request] checks an effect; resource-pack recovery must not run then. */
    @Volatile
    var probing: Boolean = false
        private set

    /**
     * Switches to [id] (`null` = off) if [loads] confirms the effect compiles; otherwise turns any effect off.
     * Returns whether [id] is active now.
     */
    fun request(id: Identifier?, loads: (Identifier) -> Boolean): Boolean {
        if (id == null) {
            current = null
            return false
        }
        val ok = try {
            probing = true
            loads(id)
        } catch (e: Exception) {
            logger.warn("Post effect {} failed to load", id, e)
            false
        } finally {
            probing = false
        }
        if (!ok) logger.warn("Ignoring post effect {} from the server: it does not exist or does not compile", id)
        current = id.takeIf { ok }
        return ok
    }

    fun clear() {
        current = null
    }
}
