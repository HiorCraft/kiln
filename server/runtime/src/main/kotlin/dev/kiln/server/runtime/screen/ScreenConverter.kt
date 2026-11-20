package dev.kiln.server.runtime.screen

import dev.kiln.server.api.screen.ButtonElement
import dev.kiln.server.api.screen.CheckboxElement
import dev.kiln.server.api.screen.CheckboxStyle as ApiCheckboxStyle
import dev.kiln.server.api.screen.ClickContext
import dev.kiln.server.api.screen.ContainerElement
import dev.kiln.server.api.screen.CustomElement
import dev.kiln.server.api.screen.DropdownElement
import dev.kiln.server.api.screen.ImageElement
import dev.kiln.server.api.screen.InputContext
import dev.kiln.server.api.screen.ItemElement
import dev.kiln.server.api.screen.ItemSlotElement
import dev.kiln.server.api.screen.Layout
import dev.kiln.server.api.screen.ModalDismissContext
import dev.kiln.server.api.screen.ModalElement
import dev.kiln.server.api.screen.PanelElement
import dev.kiln.server.api.screen.ProgressBarElement
import dev.kiln.server.api.screen.RenderedScreen
import dev.kiln.server.api.screen.ScreenElement
import dev.kiln.server.api.screen.ScreenItem
import dev.kiln.server.api.screen.ScrollPanelElement
import dev.kiln.server.api.screen.SliderElement
import dev.kiln.server.api.screen.TextElement
import dev.kiln.server.api.screen.TextFieldElement
import dev.kiln.protocol.Bounds
import dev.kiln.protocol.Button
import dev.kiln.protocol.Checkbox
import dev.kiln.protocol.CheckboxStyle
import dev.kiln.protocol.Color
import dev.kiln.protocol.Container
import dev.kiln.protocol.Custom
import dev.kiln.protocol.Dropdown
import dev.kiln.protocol.DropdownOption
import dev.kiln.protocol.Element
import dev.kiln.protocol.Image
import dev.kiln.protocol.ItemDisplay
import dev.kiln.protocol.ItemSlot
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.Modal
import dev.kiln.protocol.NumericRange
import dev.kiln.protocol.Panel
import dev.kiln.protocol.ProgressBar
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.ScrollPanel
import dev.kiln.protocol.Slider
import dev.kiln.protocol.Text
import dev.kiln.protocol.TextAlign
import dev.kiln.protocol.TextField
import dev.kiln.protocol.codec.ElementCodec
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.inventory.ItemStack
import dev.kiln.server.api.screen.TextAlign as ApiTextAlign
import org.bukkit.Color as BukkitColor

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

/** Converts DSL trees ([dev.kiln.server.api.screen]) into protocol trees ([dev.kiln.protocol]). */
object ScreenConverter {

    private class Handlers {
        val clicks = LinkedHashMap<String, (ClickContext) -> Unit>()
        val inputs = LinkedHashMap<String, (InputContext) -> Unit>()
        val dismisses = LinkedHashMap<String, (ModalDismissContext) -> Unit>()
    }

    /**
     * Converts [screen], resolving layouts into parent-relative bounds, and validates the result.
     * @throws IllegalArgumentException if the tree violates a protocol limit (size, depth, ids, strings ...).
     */
    fun convert(screen: RenderedScreen): ConvertedScreen {
        val handlers = Handlers()
        val r = screen.root
        val root = convertElement(r, Bounds(r.x, r.y, r.width, r.height), handlers) as Panel
        try {
            ElementCodec.validate(root)
        } catch (e: ProtocolException) {
            throw IllegalArgumentException("Invalid screen: ${e.message}", e)
        }
        return ConvertedScreen(root, handlers.clicks, handlers.inputs, handlers.dismisses, screen.fullscreen)
    }

    private fun convertChildren(container: ContainerElement, handlers: Handlers): Pair<List<Element>, Int> {
        val positions = LayoutResolver.resolve(container.layout, container.children)
        var bottom = 0
        val children = container.children.mapIndexed { i, child ->
            val pos = positions[i]
            bottom = maxOf(bottom, pos.y + child.height)
            convertElement(child, Bounds(pos.x, pos.y, child.width, child.height), handlers)
        }
        val padding = when (val layout = container.layout) {
            Layout.Absolute -> 0
            is Layout.Column -> layout.padding
            is Layout.Row -> layout.padding
        }
        return children to bottom + padding
    }

    private fun convertElement(element: ScreenElement, bounds: Bounds, handlers: Handlers): Element {
        val tooltip = element.tooltip?.let(::json)
        return when (element) {
            is PanelElement -> {
                element.onClick?.let { handlers.clicks[element.id] = it }
                Panel(
                    id = element.id,
                    bounds = bounds,
                    children = convertChildren(element, handlers).first,
                    background = element.background?.let(::color),
                    texture = element.texture?.let(::resourceId),
                    border = element.border?.let(::color),
                    hoverBackground = element.hoverBackground?.let(::color),
                    selectedBackground = element.selectedBackground?.let(::color),
                    selected = element.selected,
                    clickable = element.onClick != null,
                    tooltip = tooltip,
                )
            }
            is ScrollPanelElement -> {
                val (children, contentBottom) = convertChildren(element, handlers)
                ScrollPanel(
                    element.id, bounds, children, element.contentHeight ?: contentBottom,
                    element.background?.let(::color), element.border?.let(::color), element.scrollbar, tooltip,
                )
            }
            is ModalElement -> {
                element.onDismiss?.let { handlers.dismisses[element.id] = it }
                Modal(
                    element.id, bounds, convertChildren(element, handlers).first,
                    element.background?.let(::color), element.border?.let(::color), color(element.dimColor), tooltip,
                )
            }
            is TextElement -> Text(element.id, bounds, json(element.text), color(element.color), align(element.align), element.shadow, tooltip)
            is ButtonElement -> {
                element.onClick?.let { handlers.clicks[element.id] = it }
                Button(element.id, bounds, json(element.label), element.enabled, tooltip)
            }
            is ImageElement -> Image(
                element.id, bounds, resourceId(element.texture), element.u, element.v,
                element.regionWidth, element.regionHeight, element.textureWidth, element.textureHeight, tooltip,
            )
            is ItemElement -> ItemDisplay(element.id, bounds, item(element.item), element.showItemTooltip, tooltip)
            is ItemSlotElement -> {
                element.onClick?.let { handlers.clicks[element.id] = it }
                ItemSlot(
                    element.id, bounds, element.item?.let(::item), element.placeholderSprite?.let(::resourceId),
                    element.slotBackground, element.highlight, element.selected, element.enabled, element.showItemTooltip, tooltip,
                )
            }
            is ProgressBarElement -> ProgressBar(
                element.id, bounds, element.value.coerceIn(0f, 1f), color(element.color), color(element.background), tooltip,
            )
            is TextFieldElement -> {
                element.onInput?.let { handlers.inputs[element.id] = it }
                TextField(
                    element.id, bounds, element.value, element.placeholder?.let(::json), element.maxLength,
                    element.numeric?.let { NumericRange(it.min, it.max, it.integer) }, tooltip,
                )
            }
            is DropdownElement -> {
                element.onChange?.let { handlers.inputs[element.id] = it }
                Dropdown(
                    element.id, bounds, element.options.map { DropdownOption(it.value, json(it.label)) },
                    element.selected, element.placeholder?.let(::json), element.enabled, tooltip,
                )
            }
            is CheckboxElement -> {
                element.onChange?.let { handlers.inputs[element.id] = it }
                val style = when (element.style) {
                    ApiCheckboxStyle.CHECKBOX -> CheckboxStyle.CHECKBOX
                    ApiCheckboxStyle.TOGGLE -> CheckboxStyle.TOGGLE
                }
                Checkbox(element.id, bounds, element.checked, element.label?.let(::json), style, element.enabled, tooltip)
            }
            is SliderElement -> {
                element.onChange?.let { handlers.inputs[element.id] = it }
                Slider(
                    element.id, bounds, element.min, element.max, element.step, element.value,
                    element.label?.let(::json), element.enabled, tooltip,
                )
            }
            is CustomElement -> {
                element.onClick?.let { handlers.clicks[element.id] = it }
                Custom(element.id, bounds, resourceId(element.type), element.properties, tooltip)
            }
        }
    }

    /** Wire form of a [ScreenItem]; see [ItemStackData] for the format of full stacks. */
    fun item(item: ScreenItem): ItemStackData = when (item) {
        is ScreenItem.OfType -> ItemStackData(resourceId(item.type), item.count)
        is ScreenItem.OfStack -> stack(item.stack, item.count)
    }

    /**
     * Serializes [stack] with Paper's `serializeAsBytes()` (gzip NBT of `ItemStack.CODEC` + DataVersion). The amount
     * is normalized to 1 (the vanilla codec only allows 1..99, and the display count travels separately).
     * Needs a running server.
     */
    @Suppress("DEPRECATION")
    fun stack(stack: ItemStack, count: Int): ItemStackData {
        require(!stack.isEmpty) { "Empty item stacks cannot be sent" }
        val single = stack.clone().apply { amount = 1 }
        return ItemStackData(resourceId(stack.type.key), count, single.serializeAsBytes())
    }

    fun json(component: Component): String = GsonComponentSerializer.gson().serialize(component)

    fun color(color: BukkitColor): Color = Color(color.asARGB())

    /** @throws IllegalArgumentException if the key has characters Minecraft identifiers do not allow. */
    fun resourceId(key: Key): ResourceId = ResourceId.of(key.namespace(), key.value())

    fun key(id: ResourceId): Key = Key.key(id.namespace, id.path)

    private fun align(align: ApiTextAlign): TextAlign = when (align) {
        ApiTextAlign.LEFT -> TextAlign.LEFT
        ApiTextAlign.CENTER -> TextAlign.CENTER
        ApiTextAlign.RIGHT -> TextAlign.RIGHT
    }
}
