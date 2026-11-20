package dev.kiln.server.runtime.screen

import dev.kiln.server.api.screen.ClickContext
import dev.kiln.server.api.screen.InputContext
import dev.kiln.server.api.screen.ModalDismissContext
import dev.kiln.server.api.screen.RenderedScreen
import dev.kiln.protocol.element.Container
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Modal
import dev.kiln.protocol.element.Panel

/**
 * Result of converting a [RenderedScreen]: the wire tree plus the server-side handlers, indexed by element id.
 * The tree is already validated against the protocol limits.
 */
class ConvertedScreen(
    val root: Panel,
    val clickHandlers: Map<String, (ClickContext) -> Unit>,
    val inputHandlers: Map<String, (InputContext) -> Unit>,
    val dismissHandlers: Map<String, (ModalDismissContext) -> Unit> = emptyMap(),
    val fullscreen: Boolean = false,
) {
    /** All elements of [root] by id. */
    val elements: Map<String, Element>

    /** Element id -> id of the [Modal] containing it (the modal itself included); absent for elements outside modals. */
    val modalOf: Map<String, String>

    init {
        val all = LinkedHashMap<String, Element>()
        val modals = HashMap<String, String>()
        fun visit(element: Element, modal: String?) {
            all[element.id] = element
            val inModal = if (element is Modal) element.id else modal
            if (inModal != null) modals[element.id] = inModal
            if (element is Container) element.children.forEach { visit(it, inModal) }
        }
        visit(root, null)
        elements = all
        modalOf = modals
    }

    /** The modal currently receiving input (last modal among the root's children), or `null`. */
    val topModal: Modal? = root.children.lastOrNull { it is Modal } as Modal?
}
