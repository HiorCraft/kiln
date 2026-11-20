package dev.kiln.client.resource

/**
 * Tracks the resource reload Kiln starts for the extension pack (configuration phase).
 *
 * In 26.3 a failed reload never completes `Minecraft.reloadResourcePacks()`'s future; vanilla instead calls
 * `rollbackResourcePacks`, which wipes the player's own pack selection and, because the Kiln pack is required,
 * fails again and disconnects. `MinecraftReloadMixin` hands such failures to [failed] first: the extension pack is
 * removed ([removePack], which reloads without it) and the handshake gets the error, so the server disconnects with
 * a reason and the player's packs stay as they were.
 */
class KilnResourceReload {
    private var pending: ((error: String?) -> Unit)? = null

    /** A Kiln reload starts; [done] gets `null` or the error, exactly once. */
    fun begin(done: (error: String?) -> Unit) {
        pending = done
    }

    fun completed() {
        val done = pending ?: return
        pending = null
        done(null)
    }

    /** `true` if [error] ended a Kiln reload (then [removePack] ran and the handshake was told). */
    fun failed(error: Throwable, removePack: () -> Unit): Boolean {
        val done = pending ?: return false
        pending = null
        removePack()
        done(error.message ?: error.javaClass.simpleName)
        return true
    }

    companion object {
        /** The client's tracker (render thread only). */
        @JvmField
        val CLIENT: KilnResourceReload = KilnResourceReload()
    }
}
