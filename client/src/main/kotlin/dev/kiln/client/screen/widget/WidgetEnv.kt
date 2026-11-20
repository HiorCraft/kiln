package dev.kiln.client.screen.widget

import dev.kiln.client.screen.KilnScreen
import dev.kiln.protocol.message.ServerboundMessage
import net.minecraft.client.gui.Font

/** What the widgets of a [KilnScreen] need from it. */
interface WidgetEnv {
    val screenId: Int
    val font: Font
    fun send(message: ServerboundMessage): Boolean
    fun toggleDropdown(head: DropdownHead)
    fun isDropdownOpen(head: DropdownHead): Boolean
}
