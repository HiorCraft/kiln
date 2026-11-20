package dev.kiln.client.lua.screen

/** What a [LuaScreenSession] needs from the Minecraft screen showing it. */
interface ScreenView {
    /** A new text field; [onEdit] is called on every edit by the player (not for [TextBox.setValue]). */
    fun createTextBox(onEdit: (String) -> Unit): TextBox
}
