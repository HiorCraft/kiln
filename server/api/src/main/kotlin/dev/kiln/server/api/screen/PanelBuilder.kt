package dev.kiln.server.api.screen

import dev.kiln.server.api.KilnDsl
import org.bukkit.Color

/** Content of a nested panel. */
@KilnDsl
public class PanelBuilder internal constructor(ids: IdAllocator) : ContainerBuilder(ids) {
    /** Background while hovered (falls back to [background]). */
    public var hoverBackground: Color? = null

    /** Background while [selected] (falls back to [background]). */
    public var selectedBackground: Color? = null
    public var selected: Boolean = false
    internal var clickHandler: ((ClickContext) -> Unit)? = null

    /** Makes the panel clickable (tiles, tabs); requires an explicit panel id. */
    public fun onClick(handler: (ClickContext) -> Unit) {
        clickHandler = handler
    }

    internal fun build(id: String, x: Int, y: Int, width: Int, height: Int): PanelElement =
        PanelElement(
            id, x, y, width, height, children(), layout, background, texture, border,
            hoverBackground, selectedBackground, selected, tooltip, clickHandler,
        )
}
