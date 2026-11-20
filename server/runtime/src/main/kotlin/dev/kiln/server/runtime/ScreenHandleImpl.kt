package dev.kiln.server.runtime

import dev.kiln.server.api.screen.CloseContext
import dev.kiln.server.api.screen.CloseReason
import dev.kiln.server.api.screen.ScreenBuilder
import dev.kiln.server.api.screen.ScreenDefinition
import dev.kiln.server.api.screen.ScreenHandle
import dev.kiln.server.api.screen.Viewport
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Slider
import dev.kiln.protocol.element.TextField
import dev.kiln.protocol.message.UpdateScreen
import dev.kiln.server.runtime.screen.ConvertedScreen
import dev.kiln.server.runtime.screen.ScreenConverter
import org.bukkit.entity.Player

/** Server-side state of one open screen. */
class ScreenHandleImpl(
    private val bridge: KilnImpl,
    val session: PlayerSession,
    override val id: Int,
    definition: ScreenDefinition,
) : ScreenHandle {
    override var definition: ScreenDefinition = definition
        private set

    override var isOpen: Boolean = false
        internal set

    override val player: Player get() = session.player

    override val viewport: Viewport get() = session.viewport ?: Viewport.DEFAULT

    /** Last tree sent to the client (with its handlers). */
    var tree: ConvertedScreen? = null
        private set

    var closeHandler: ((CloseContext) -> Unit)? = null
        private set

    /**
     * Current input values (text fields, dropdowns, checkboxes, sliders): last value reported by the client, or the
     * value the server last changed.
     */
    private val inputValues = HashMap<String, String>()

    /** Renders [definition] for the current viewport and converts it; throws without changing any state. */
    fun render(definition: ScreenDefinition = this.definition): Pair<ConvertedScreen, ((CloseContext) -> Unit)?> {
        val rendered = definition.render(viewport)
        return ScreenConverter.convert(rendered) to rendered.onClose
    }

    /** Stores a newly sent tree; returns `false` if it is identical to the previous one. */
    fun apply(converted: ConvertedScreen, onClose: ((CloseContext) -> Unit)?): Boolean {
        val previous = tree
        closeHandler = onClose
        tree = converted
        val inputs = converted.elements.values.filter { inputValueOf(it) != null || it is Dropdown }
        inputValues.keys.retainAll(inputs.mapTo(HashSet()) { it.id })
        for (input in inputs) {
            val newValue = inputValueOf(input)
            val old = previous?.elements?.get(input.id)
            val changed = old == null || old::class != input::class || inputValueOf(old) != newValue
            // the client adopts the server value only for new elements or when the server changed it
            if (changed || input.id !in inputValues) {
                if (newValue == null) inputValues.remove(input.id) else inputValues[input.id] = newValue
            }
        }
        return previous == null || previous.root != converted.root || previous.fullscreen != converted.fullscreen
    }

    /** Wire value of an input element as the client would report it; `null` for non-inputs / no selection. */
    private fun inputValueOf(element: Element): String? = when (element) {
        is TextField -> element.value
        is Dropdown -> element.selected
        is Checkbox -> element.checked.toString()
        is Slider -> element.value.toString()
        else -> null
    }

    fun clientInput(elementId: String, value: String) {
        inputValues[elementId] = value
    }

    override fun refresh() {
        if (!isOpen) return
        sendUpdate(definition)
    }

    override fun update(content: ScreenBuilder.() -> Unit) {
        if (!isOpen) return
        val newDefinition = definition.withContent(content)
        sendUpdate(newDefinition)
        definition = newDefinition
    }

    private fun sendUpdate(newDefinition: ScreenDefinition) {
        val (converted, onClose) = render(newDefinition)
        if (apply(converted, onClose)) bridge.send(session, UpdateScreen(id, converted.root, converted.fullscreen))
    }

    override fun inputValue(elementId: String): String? = inputValues[elementId]

    override fun close() {
        bridge.closeScreen(this, CloseReason.SERVER, notifyClient = true)
    }

    override fun toString(): String = "Screen($id of ${player.name}, open=$isOpen)"
}
