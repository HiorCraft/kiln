package dev.kiln.server.runtime.screen

import dev.kiln.server.api.screen.ClickContext
import dev.kiln.server.api.screen.InputContext
import dev.kiln.server.api.screen.ModalDismissContext

/** Server-side handlers collected while converting a screen, by element id. */
internal class ScreenHandlers {
    val clicks = LinkedHashMap<String, (ClickContext) -> Unit>()
    val inputs = LinkedHashMap<String, (InputContext) -> Unit>()
    val dismisses = LinkedHashMap<String, (ModalDismissContext) -> Unit>()
}
