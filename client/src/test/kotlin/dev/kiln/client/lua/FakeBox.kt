package dev.kiln.client.lua

import dev.kiln.client.lua.graphics.DrawTarget
import dev.kiln.client.lua.screen.TextBox
import dev.kiln.protocol.element.NumericRange

class FakeBox : TextBox {
    private var current: String = ""
    override val value: String get() = current
    override var focused: Boolean = false
    var maxLength = 0
    var placeholder = ""
    var numeric: NumericRange? = null
    var enabled = true
    var rect = IntArray(4)
    var draws = 0
    var onEdit: (String) -> Unit = {}

    /** Order of setValue / place calls (a vanilla EditBox must know its width before a value is set). */
    val calls = ArrayList<String>()

    override fun setValue(value: String) { calls += "value"; current = value.take(maxLength.takeIf { it > 0 } ?: Int.MAX_VALUE) }
    override fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean) {
        this.maxLength = maxLength; this.placeholder = placeholder; this.numeric = numeric; this.enabled = enabled
    }
    override fun place(x: Int, y: Int, width: Int, height: Int) { calls += "place"; rect = intArrayOf(x, y, width, height) }
    override fun draw(target: DrawTarget, mouseX: Int, mouseY: Int) { draws++ }
    override fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean) {}

    /** The player types: the value changes and the edit is reported. */
    fun type(text: String) {
        current = (current + text).take(maxLength)
        onEdit(current)
    }
}
