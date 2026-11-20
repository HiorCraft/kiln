package dev.kiln.client.resource

/**
 * A resource reload requested while it may not start yet: after a disconnect the extension pack must go, but
 * `Minecraft.disconnect` drops every queued main-thread task, including the ones a reload started inside the
 * disconnect would need to finish (the loading overlay then never closes). [tick] starts it once [mayRun] allows.
 */
class DeferredReload(private val mayRun: () -> Boolean, private val reload: () -> Unit) {
    private var requested = false

    fun request() {
        requested = true
    }

    /** Called every client tick. */
    fun tick() {
        if (!requested || !mayRun()) return
        requested = false
        reload()
    }
}
