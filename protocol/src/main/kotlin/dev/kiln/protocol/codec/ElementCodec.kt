package dev.kiln.protocol.codec

import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Button
import dev.kiln.protocol.Checkbox
import dev.kiln.protocol.CheckboxStyle
import dev.kiln.protocol.Custom
import dev.kiln.protocol.Dropdown
import dev.kiln.protocol.DropdownOption
import dev.kiln.protocol.Element
import dev.kiln.protocol.Ids
import dev.kiln.protocol.Image
import dev.kiln.protocol.ItemDisplay
import dev.kiln.protocol.ItemSlot
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.Modal
import dev.kiln.protocol.NumericRange
import dev.kiln.protocol.Panel
import dev.kiln.protocol.ProgressBar
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits.MAX_COORDINATE
import dev.kiln.protocol.ProtocolLimits.MAX_DEPTH
import dev.kiln.protocol.ProtocolLimits.MAX_DROPDOWN_OPTIONS
import dev.kiln.protocol.ProtocolLimits.MAX_ELEMENTS
import dev.kiln.protocol.ProtocolLimits.MAX_ID_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_ITEM_COUNT
import dev.kiln.protocol.ProtocolLimits.MAX_ITEM_NBT_BYTES
import dev.kiln.protocol.ProtocolLimits.MAX_NUMERIC_VALUE
import dev.kiln.protocol.ProtocolLimits.MAX_OPTION_LABEL_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_OPTION_VALUE_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_PROPERTIES
import dev.kiln.protocol.ProtocolLimits.MAX_PROPERTY_VALUE_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_SCROLL_CONTENT_HEIGHT
import dev.kiln.protocol.ProtocolLimits.MAX_SIZE
import dev.kiln.protocol.ProtocolLimits.MAX_STRING_LENGTH
import dev.kiln.protocol.ProtocolLimits.MAX_TEXTURE_SIZE
import dev.kiln.protocol.ProtocolLimits.MAX_TEXT_FIELD_LENGTH
import dev.kiln.protocol.ScrollPanel
import dev.kiln.protocol.Slider
import dev.kiln.protocol.Text
import dev.kiln.protocol.TextAlign
import dev.kiln.protocol.TextField

/**
 * Binary codec for GUI trees. Element layout: `varint type`, `string id`, bounds, `nullable string tooltip`,
 * type-specific fields. Tree-wide rules (element count, depth, unique ids, modals only as direct root children)
 * are checked on both encode and decode, as are all value ranges.
 */
public object ElementCodec {
    private const val PANEL = 0
    private const val TEXT = 1
    private const val BUTTON = 2
    private const val IMAGE = 3
    private const val ITEM_DISPLAY = 4
    private const val PROGRESS_BAR = 5
    private const val TEXT_FIELD = 6
    private const val CUSTOM = 7
    private const val ITEM_SLOT = 8
    private const val DROPDOWN = 9
    private const val CHECKBOX = 10
    private const val SLIDER = 11
    private const val SCROLL_PANEL = 12
    private const val MODAL = 13

    /** Depth of the root's direct children, the only place where a [Modal] is allowed. */
    private const val MODAL_DEPTH = 2

    /** Validates a tree without encoding it; throws [ProtocolException]. */
    public fun validate(root: Element) {
        encode(ProtocolWriter(), root)
    }

    public fun encode(writer: ProtocolWriter, root: Element) {
        writeElement(writer, root, TreeState(), depth = 1)
    }

    public fun decode(reader: ProtocolReader): Element = readElement(reader, TreeState(), depth = 1)

    private class TreeState {
        var count = 0
        val ids = HashSet<String>()

        fun enter(id: String, depth: Int) {
            if (depth > MAX_DEPTH) throw ProtocolException("GUI tree deeper than $MAX_DEPTH")
            if (++count > MAX_ELEMENTS) throw ProtocolException("GUI tree has more than $MAX_ELEMENTS elements")
            if (!Ids.isValidElementId(id)) throw ProtocolException("Invalid element id '$id'")
            if (!ids.add(id)) throw ProtocolException("Duplicate element id '$id'")
        }
    }

    // ---- items (shared with MessageCodec) -------------------------------------------------------------------

    public fun writeItem(w: ProtocolWriter, item: ItemStackData) {
        w.resourceId(item.itemId)
        w.varInt(item.count, 1, MAX_ITEM_COUNT, "item count")
        val nbt = item.nbt
        w.boolean(nbt != null)
        if (nbt != null) {
            checkNbt(nbt)
            w.byteArray(nbt, MAX_ITEM_NBT_BYTES, "item nbt")
        }
    }

    public fun readItem(r: ProtocolReader): ItemStackData {
        val itemId = r.resourceId("item id")
        val count = r.varInt(1, MAX_ITEM_COUNT, "item count")
        val nbt = if (r.boolean()) r.byteArray(MAX_ITEM_NBT_BYTES, "item nbt").also(::checkNbt) else null
        return ItemStackData(itemId, count, nbt)
    }

    public fun writeNullableItem(w: ProtocolWriter, item: ItemStackData?) {
        w.boolean(item != null)
        if (item != null) writeItem(w, item)
    }

    public fun readNullableItem(r: ProtocolReader): ItemStackData? = if (r.boolean()) readItem(r) else null

    private fun checkNbt(nbt: ByteArray) {
        if (nbt.size < 2 || nbt[0] != 0x1F.toByte() || nbt[1] != 0x8B.toByte()) {
            throw ProtocolException("Item nbt is not gzip data (expected Paper ItemStack.serializeAsBytes())")
        }
    }

    // ---- encode ---------------------------------------------------------------------------------------------

    private fun typeOf(element: Element): Int = when (element) {
        is Panel -> PANEL
        is Text -> TEXT
        is Button -> BUTTON
        is Image -> IMAGE
        is ItemDisplay -> ITEM_DISPLAY
        is ProgressBar -> PROGRESS_BAR
        is TextField -> TEXT_FIELD
        is Custom -> CUSTOM
        is ItemSlot -> ITEM_SLOT
        is Dropdown -> DROPDOWN
        is Checkbox -> CHECKBOX
        is Slider -> SLIDER
        is ScrollPanel -> SCROLL_PANEL
        is Modal -> MODAL
    }

    private fun writeElement(w: ProtocolWriter, element: Element, state: TreeState, depth: Int) {
        state.enter(element.id, depth)
        val type = typeOf(element)
        if (type == MODAL && depth != MODAL_DEPTH) throw ProtocolException("Modal '${element.id}' must be a direct child of the root")
        w.varInt(type)
        w.string(element.id, MAX_ID_LENGTH, "element id")
        writeBounds(w, element.bounds)
        w.nullableString(element.tooltip, MAX_STRING_LENGTH, "tooltip")
        val children = { list: List<Element> -> w.list(list, MAX_ELEMENTS, "children") { writeElement(w, it, state, depth + 1) } }
        when (element) {
            is Panel -> {
                w.nullableColor(element.background)
                w.nullableResourceId(element.texture)
                w.nullableColor(element.border)
                w.nullableColor(element.hoverBackground)
                w.nullableColor(element.selectedBackground)
                w.boolean(element.selected)
                w.boolean(element.clickable)
                children(element.children)
            }
            is ScrollPanel -> {
                w.varInt(element.contentHeight, 0, MAX_SCROLL_CONTENT_HEIGHT, "content height")
                w.nullableColor(element.background)
                w.nullableColor(element.border)
                w.boolean(element.scrollbar)
                children(element.children)
            }
            is Modal -> {
                w.nullableColor(element.background)
                w.nullableColor(element.border)
                w.color(element.dimColor)
                children(element.children)
            }
            is Text -> {
                w.string(element.text, MAX_STRING_LENGTH, "text")
                w.color(element.color)
                w.varInt(element.align.ordinal)
                w.boolean(element.shadow)
            }
            is Button -> {
                w.string(element.label, MAX_STRING_LENGTH, "label")
                w.boolean(element.enabled)
            }
            is Image -> {
                w.resourceId(element.texture)
                checkFinite(element.u, "u")
                checkFinite(element.v, "v")
                w.float(element.u)
                w.float(element.v)
                w.varInt(element.regionWidth, 0, MAX_TEXTURE_SIZE, "region width")
                w.varInt(element.regionHeight, 0, MAX_TEXTURE_SIZE, "region height")
                w.varInt(element.textureWidth, 1, MAX_TEXTURE_SIZE, "texture width")
                w.varInt(element.textureHeight, 1, MAX_TEXTURE_SIZE, "texture height")
            }
            is ItemDisplay -> {
                writeItem(w, element.item)
                w.boolean(element.showItemTooltip)
            }
            is ItemSlot -> {
                writeNullableItem(w, element.item)
                w.nullableResourceId(element.placeholderSprite)
                w.boolean(element.slotBackground)
                w.boolean(element.highlight)
                w.boolean(element.selected)
                w.boolean(element.enabled)
                w.boolean(element.showItemTooltip)
            }
            is ProgressBar -> {
                checkProgress(element.value)
                w.float(element.value)
                w.color(element.color)
                w.color(element.background)
            }
            is TextField -> {
                w.varInt(element.maxLength, 1, MAX_TEXT_FIELD_LENGTH, "max length")
                w.string(element.value, element.maxLength, "text field value")
                w.nullableString(element.placeholder, MAX_STRING_LENGTH, "placeholder")
                val numeric = element.numeric
                w.boolean(numeric != null)
                if (numeric != null) {
                    checkRange(numeric.min, numeric.max, "numeric range")
                    w.double(numeric.min)
                    w.double(numeric.max)
                    w.boolean(numeric.integer)
                    checkNumericValue(numeric, element.value)
                }
            }
            is Dropdown -> {
                checkDropdown(element.options, element.selected)
                w.list(element.options, MAX_DROPDOWN_OPTIONS, "dropdown options") {
                    w.string(it.value, MAX_OPTION_VALUE_LENGTH, "option value")
                    w.string(it.label, MAX_OPTION_LABEL_LENGTH, "option label")
                }
                w.nullableString(element.selected, MAX_OPTION_VALUE_LENGTH, "selected option")
                w.nullableString(element.placeholder, MAX_STRING_LENGTH, "placeholder")
                w.boolean(element.enabled)
            }
            is Checkbox -> {
                w.boolean(element.checked)
                w.nullableString(element.label, MAX_STRING_LENGTH, "label")
                w.varInt(element.style.ordinal)
                w.boolean(element.enabled)
            }
            is Slider -> {
                checkSlider(element.min, element.max, element.step, element.value)
                w.double(element.min)
                w.double(element.max)
                w.double(element.step)
                w.double(element.value)
                w.nullableString(element.label, MAX_STRING_LENGTH, "label")
                w.boolean(element.enabled)
            }
            is Custom -> {
                w.resourceId(element.type)
                w.list(element.properties.entries.toList(), MAX_PROPERTIES, "properties") { (key, value) ->
                    w.string(key, MAX_ID_LENGTH, "property key")
                    w.string(value, MAX_PROPERTY_VALUE_LENGTH, "property value")
                }
            }
        }
    }

    private fun writeBounds(w: ProtocolWriter, b: Bounds) {
        checkCoordinate(b.x, "x")
        checkCoordinate(b.y, "y")
        w.signedVarInt(b.x)
        w.signedVarInt(b.y)
        w.varInt(b.width, 0, MAX_SIZE, "width")
        w.varInt(b.height, 0, MAX_SIZE, "height")
    }

    // ---- decode ---------------------------------------------------------------------------------------------

    private fun readElement(r: ProtocolReader, state: TreeState, depth: Int): Element {
        // depth/count are checked before reading children, so hostile nesting cannot overflow the stack
        if (depth > MAX_DEPTH) throw ProtocolException("GUI tree deeper than $MAX_DEPTH")
        val type = r.varInt()
        val id = r.string(MAX_ID_LENGTH, "element id")
        state.enter(id, depth)
        if (type == MODAL && depth != MODAL_DEPTH) throw ProtocolException("Modal '$id' must be a direct child of the root")
        val bounds = readBounds(r)
        val tooltip = r.nullableString(MAX_STRING_LENGTH, "tooltip")
        val children = { r.list(MAX_ELEMENTS, "children") { readElement(r, state, depth + 1) } }
        return when (type) {
            PANEL -> {
                val background = r.nullableColor()
                val texture = r.nullableResourceId("panel texture")
                val border = r.nullableColor()
                val hoverBackground = r.nullableColor()
                val selectedBackground = r.nullableColor()
                val selected = r.boolean()
                val clickable = r.boolean()
                Panel(id, bounds, children(), background, texture, border, hoverBackground, selectedBackground, selected, clickable, tooltip)
            }
            SCROLL_PANEL -> {
                val contentHeight = r.varInt(0, MAX_SCROLL_CONTENT_HEIGHT, "content height")
                val background = r.nullableColor()
                val border = r.nullableColor()
                val scrollbar = r.boolean()
                ScrollPanel(id, bounds, children(), contentHeight, background, border, scrollbar, tooltip)
            }
            MODAL -> {
                val background = r.nullableColor()
                val border = r.nullableColor()
                val dim = r.color()
                Modal(id, bounds, children(), background, border, dim, tooltip)
            }
            TEXT -> Text(
                id, bounds,
                text = r.string(MAX_STRING_LENGTH, "text"),
                color = r.color(),
                align = r.varInt(0, TextAlign.entries.size - 1, "text align").let { TextAlign.entries[it] },
                shadow = r.boolean(),
                tooltip = tooltip,
            )
            BUTTON -> Button(
                id, bounds,
                label = r.string(MAX_STRING_LENGTH, "label"),
                enabled = r.boolean(),
                tooltip = tooltip,
            )
            IMAGE -> Image(
                id, bounds,
                texture = r.resourceId("image texture"),
                u = r.float().also { checkFinite(it, "u") },
                v = r.float().also { checkFinite(it, "v") },
                regionWidth = r.varInt(0, MAX_TEXTURE_SIZE, "region width"),
                regionHeight = r.varInt(0, MAX_TEXTURE_SIZE, "region height"),
                textureWidth = r.varInt(1, MAX_TEXTURE_SIZE, "texture width"),
                textureHeight = r.varInt(1, MAX_TEXTURE_SIZE, "texture height"),
                tooltip = tooltip,
            )
            ITEM_DISPLAY -> ItemDisplay(id, bounds, item = readItem(r), showItemTooltip = r.boolean(), tooltip = tooltip)
            ITEM_SLOT -> ItemSlot(
                id, bounds,
                item = readNullableItem(r),
                placeholderSprite = r.nullableResourceId("placeholder sprite"),
                slotBackground = r.boolean(),
                highlight = r.boolean(),
                selected = r.boolean(),
                enabled = r.boolean(),
                showItemTooltip = r.boolean(),
                tooltip = tooltip,
            )
            PROGRESS_BAR -> ProgressBar(
                id, bounds,
                value = r.float().also { checkProgress(it) },
                color = r.color(),
                background = r.color(),
                tooltip = tooltip,
            )
            TEXT_FIELD -> {
                val maxLength = r.varInt(1, MAX_TEXT_FIELD_LENGTH, "max length")
                val value = r.string(maxLength, "text field value")
                val placeholder = r.nullableString(MAX_STRING_LENGTH, "placeholder")
                val numeric = if (r.boolean()) {
                    val min = r.double()
                    val max = r.double()
                    checkRange(min, max, "numeric range")
                    NumericRange(min, max, r.boolean()).also { checkNumericValue(it, value) }
                } else {
                    null
                }
                TextField(id, bounds, value, placeholder, maxLength, numeric, tooltip)
            }
            DROPDOWN -> {
                val options = r.list(MAX_DROPDOWN_OPTIONS, "dropdown options") {
                    DropdownOption(r.string(MAX_OPTION_VALUE_LENGTH, "option value"), r.string(MAX_OPTION_LABEL_LENGTH, "option label"))
                }
                val selected = r.nullableString(MAX_OPTION_VALUE_LENGTH, "selected option")
                checkDropdown(options, selected)
                Dropdown(id, bounds, options, selected, r.nullableString(MAX_STRING_LENGTH, "placeholder"), r.boolean(), tooltip)
            }
            CHECKBOX -> Checkbox(
                id, bounds,
                checked = r.boolean(),
                label = r.nullableString(MAX_STRING_LENGTH, "label"),
                style = r.varInt(0, CheckboxStyle.entries.size - 1, "checkbox style").let { CheckboxStyle.entries[it] },
                enabled = r.boolean(),
                tooltip = tooltip,
            )
            SLIDER -> {
                val min = r.double()
                val max = r.double()
                val step = r.double()
                val value = r.double()
                checkSlider(min, max, step, value)
                Slider(id, bounds, min, max, step, value, r.nullableString(MAX_STRING_LENGTH, "label"), r.boolean(), tooltip)
            }
            CUSTOM -> {
                val customType = r.resourceId("custom type")
                val properties = LinkedHashMap<String, String>()
                r.list(MAX_PROPERTIES, "properties") {
                    val key = r.string(MAX_ID_LENGTH, "property key")
                    val value = r.string(MAX_PROPERTY_VALUE_LENGTH, "property value")
                    if (properties.put(key, value) != null) throw ProtocolException("Duplicate property '$key'")
                }
                Custom(id, bounds, customType, properties, tooltip)
            }
            else -> throw ProtocolException("Unknown element type $type")
        }
    }

    private fun readBounds(r: ProtocolReader): Bounds {
        val x = r.signedVarInt().also { checkCoordinate(it, "x") }
        val y = r.signedVarInt().also { checkCoordinate(it, "y") }
        return Bounds(x, y, r.varInt(0, MAX_SIZE, "width"), r.varInt(0, MAX_SIZE, "height"))
    }

    // ---- value checks ---------------------------------------------------------------------------------------

    private fun checkCoordinate(value: Int, what: String) {
        if (value < -MAX_COORDINATE || value > MAX_COORDINATE) throw ProtocolException("$what out of range: $value")
    }

    private fun checkFinite(value: Float, what: String) {
        if (!value.isFinite()) throw ProtocolException("$what is not finite")
    }

    private fun checkProgress(value: Float) {
        if (!(value >= 0f && value <= 1f)) throw ProtocolException("Progress value out of range: $value")
    }

    private fun checkNumber(value: Double, what: String) {
        if (!value.isFinite() || value < -MAX_NUMERIC_VALUE || value > MAX_NUMERIC_VALUE) {
            throw ProtocolException("$what out of range: $value")
        }
    }

    private fun checkRange(min: Double, max: Double, what: String) {
        checkNumber(min, "$what min")
        checkNumber(max, "$what max")
        if (min > max) throw ProtocolException("$what: min $min > max $max")
    }

    private fun checkNumericValue(range: NumericRange, value: String) {
        if (value.isNotEmpty() && !range.accepts(value)) throw ProtocolException("Text field value '$value' outside $range")
    }

    private fun checkSlider(min: Double, max: Double, step: Double, value: Double) {
        checkRange(min, max, "slider")
        checkNumber(step, "slider step")
        if (step < 0.0) throw ProtocolException("Slider step is negative: $step")
        checkNumber(value, "slider value")
        if (value < min || value > max) throw ProtocolException("Slider value $value outside $min..$max")
    }

    private fun checkDropdown(options: List<DropdownOption>, selected: String?) {
        val values = HashSet<String>()
        for (option in options) if (!values.add(option.value)) throw ProtocolException("Duplicate dropdown option '${option.value}'")
        if (selected != null && selected !in values) throw ProtocolException("Selected dropdown option '$selected' does not exist")
    }
}
