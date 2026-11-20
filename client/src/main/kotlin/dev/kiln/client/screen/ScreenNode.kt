package dev.kiln.client.screen

import dev.kiln.protocol.element.Button
import dev.kiln.protocol.element.Checkbox
import dev.kiln.protocol.element.Dropdown
import dev.kiln.protocol.element.Element
import dev.kiln.protocol.element.Slider
import dev.kiln.protocol.element.Text
import dev.kiln.protocol.element.TextField
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

/** A placed element of a [KilnScreen] plus data derived once per tree (parsed components). */
internal class ScreenNode(val index: Int, val placed: PlacedElement) {
    val element: Element get() = placed.element

    val text: Component? = when (val e = placed.element) {
        is Text -> Components.parse(e.text)
        is Button -> Components.parse(e.label)
        is Checkbox -> Components.parse(e.label)
        is Slider -> Components.parse(e.label)
        else -> null
    }
    val placeholder: Component? = when (val e = placed.element) {
        is TextField -> Components.parse(e.placeholder)
        is Dropdown -> Components.parse(e.placeholder)
        else -> null
    }
    val optionLabels: List<Component> = (placed.element as? Dropdown)?.options
        ?.map { Components.parse(it.label) ?: Component.literal(it.value) } ?: emptyList()
    private val tooltip: Component? = Components.parse(placed.element.tooltip)
    private var tooltipLines: List<FormattedCharSequence>? = null

    /** The element's own tooltip, wrapped (and split at `\n`). */
    fun tooltipLines(font: Font): List<FormattedCharSequence> {
        val component = tooltip ?: return emptyList()
        return tooltipLines ?: font.split(component, TOOLTIP_WIDTH).also { tooltipLines = it }
    }

    private companion object {
        const val TOOLTIP_WIDTH = 200
    }
}
