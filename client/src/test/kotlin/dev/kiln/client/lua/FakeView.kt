package dev.kiln.client.lua

import dev.kiln.client.lua.screen.ScreenView
import dev.kiln.client.lua.screen.TextBox

class FakeView : ScreenView {
    val boxes = ArrayList<FakeBox>()
    override fun createTextBox(onEdit: (String) -> Unit): TextBox = FakeBox().also { it.onEdit = onEdit; boxes += it }
}
