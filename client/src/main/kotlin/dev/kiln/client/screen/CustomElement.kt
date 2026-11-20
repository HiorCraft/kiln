package dev.kiln.client.screen

import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.WidgetInstance
import dev.kiln.protocol.element.Custom
import net.minecraft.resources.Identifier
import java.util.Collections

/**
 * A `Custom` element of a [KilnScreen]: its bounds, properties (updated in place) and Lua widget instance (kept
 * across updates, created by [createWidget]).
 */
internal class CustomElement(
    val elementId: String,
    private val createWidget: (type: Identifier) -> WidgetInstance?,
) {
    var type: Identifier = Identifier.fromNamespaceAndPath("kiln", "unknown")
        private set
    var x = 0
        private set
    var y = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    private val props = LinkedHashMap<String, String>()
    val properties: Map<String, String> = Collections.unmodifiableMap(props)
    private var instance: WidgetInstance? = null
    private var created = false

    fun apply(e: Custom) {
        val newType = Identifier.tryParse(e.type.toString()) ?: type
        if (newType != type) {
            instance = null
            created = false
        }
        type = newType
        props.clear()
        props.putAll(e.properties)
    }

    /** The Lua widget (created on first use), or `null` for a placeholder (unknown type, extension unloaded). */
    fun widget(): WidgetInstance? {
        if (!created) {
            created = true
            instance = createWidget(type)
        }
        return instance?.takeIf { it.extension.alive }
    }

    fun place(r: Rect) {
        x = r.x0
        y = r.y0
        width = r.width
        height = r.height
    }
}
