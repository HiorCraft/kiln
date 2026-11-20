package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import org.bukkit.Color

/** Content of a modal dialog ([texture] is ignored). */
@KilnDsl
public class ModalBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    /** Color drawn over the whole window below the modal. */
    public var dimColor: Color = Color.fromARGB(0xA0, 0, 0, 0)
    internal var dismissHandler: ((ModalDismissContext) -> Unit)? = null

    init {
        background = Color.fromARGB(0xF0, 0x10, 0x10, 0x10)
        border = Color.fromRGB(0x808080)
    }

    /**
     * Called when the player presses Escape while this modal is the topmost one. The screen stays open; usually you
     * re-render without the modal. Without a handler Escape does nothing while the modal is shown.
     */
    public fun onDismiss(handler: (ModalDismissContext) -> Unit) {
        dismissHandler = handler
    }

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int): ModalElement =
        ModalElement(id, x, y, width, height, children(), layout, background, border, dimColor, dismissHandler, tooltip)
}
