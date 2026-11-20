package dev.kiln.protocol.element

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_DROPDOWN_OPTIONS
import dev.kiln.protocol.ProtocolLimits.MAX_OPTION_VALUE_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.StringCodec
import dev.kiln.protocol.codec.requireDistinct

/**
 * Selection box. Clicking opens the option list, which the client draws as an overlay above everything (modals
 * included). Choosing an option sends [ScreenInput] with its [DropdownOption.value]. Like a [TextField], the client
 * keeps the local choice across updates until the server changes [selected].
 */
public data class Dropdown(
    override val id: String,
    override val bounds: Bounds,
    val options: List<DropdownOption>,
    /** Value of the selected option, or `null` for none (shows [placeholder]). Must be one of [options]. */
    val selected: String? = null,
    /** Component JSON. */
    val placeholder: String? = null,
    val enabled: Boolean = true,
    override val tooltip: String? = null,
) : Element {
    /** Wire codec: the element header, then the fields below. */
    public companion object : Codec<Dropdown> {
        private val options = DropdownOption.list(MAX_DROPDOWN_OPTIONS, "dropdown options")
        private val selected = StringCodec(MAX_OPTION_VALUE_LENGTH, "selected option").nullable()
        private val placeholder = StringCodec(MAX_STRING_LENGTH, "placeholder").nullable()

        override fun encode(writer: ProtocolWriter, value: Dropdown) {
            checkSelection(value)
            ElementHeader.encode(writer, ElementHeader.of(value))
            options.encode(writer, value.options)
            selected.encode(writer, value.selected)
            placeholder.encode(writer, value.placeholder)
            writer.boolean(value.enabled)
        }

        override fun decode(reader: ProtocolReader): Dropdown {
            val header = ElementHeader.decode(reader)
            return Dropdown(
                id = header.id,
                bounds = header.bounds,
                options = options.decode(reader),
                selected = selected.decode(reader),
                placeholder = placeholder.decode(reader),
                enabled = reader.boolean(),
                tooltip = header.tooltip,
            ).also(::checkSelection)
        }

        /** Option values are unique and the selected value is one of them. */
        private fun checkSelection(dropdown: Dropdown) {
            val values = dropdown.options.map { it.value }
            requireDistinct(values) { "Duplicate dropdown option '$it'" }
            val selected = dropdown.selected
            if (selected != null && selected !in values) {
                throw ProtocolException("Selected dropdown option '$selected' does not exist")
            }
        }
    }
}
