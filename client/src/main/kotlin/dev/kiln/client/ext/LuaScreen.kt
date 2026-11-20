package dev.kiln.client.ext

import com.mojang.blaze3d.platform.InputConstants
import dev.kiln.client.lua.screen.KeyResult
import dev.kiln.client.lua.screen.LuaScreenSession
import dev.kiln.client.lua.screen.ModifierKeys
import dev.kiln.client.lua.screen.ScreenView
import dev.kiln.client.lua.screen.TextBox
import dev.kiln.client.screen.MouseButtons
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Minecraft side of a Lua screen ([LuaScreenSession]): forwards layout, frames and input, and provides the native
 * text inputs ([EditBoxTextBox]). Nothing here is a vanilla child widget: the session decides focus and hit tests,
 * text inputs are drawn inside the Lua render call (so they are layered and clipped like Lua drawing).
 *
 * Keys: the focused text input first (editing keys), then Lua `key`; Tab cycles inputs, Escape closes unless Lua
 * handled it; Shift+Escape and a third Escape within 1.5 s always close (see [LuaScreenSession.keyPressed]). The
 * screen closes on disconnect (the runtime replaces it) without running Lua.
 */
class LuaScreen(val session: LuaScreenSession, private val textures: ExtensionTextures) : Screen(Component.empty()), ScreenView {
    /** Set when the screen goes away without Lua being told (disconnect, extension unloaded). */
    var silent: Boolean = false

    /** Mouse event in progress (text inputs need the vanilla event for cursor placement). */
    private var currentEvent: MouseButtonEvent? = null
    private var dragging: EditBoxTextBox? = null

    init {
        session.view = this
    }

    override fun init() = layout()

    override fun repositionElements() = layout()

    private fun layout() {
        session.layout(width, height, minecraft.window.guiScale.toDouble())
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics) else super.extractBackground(graphics, mouseX, mouseY, a)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        session.render(GuiDrawTarget(graphics, font, textures, mouseX, mouseY), mouseX, mouseY)
    }

    // ---- input --------------------------------------------------------------------------------------------

    private fun mods(event: InputWithModifiers) = ModifierKeys(event.hasShiftDown(), event.hasControlDown(), event.hasAltDown())

    override fun keyPressed(event: KeyEvent): Boolean {
        val name = LuaScreenSession.shortKeyName(InputConstants.getKey(event).name)
        // Escape never goes to a text field: the session decides (including the forced way out, Shift+Escape / 3x)
        val box = session.focused?.box as? EditBoxTextBox
        if (name != "escape" && box != null && box.edit.keyPressed(event)) return true
        return when (session.keyPressed(name, mods(event))) {
            KeyResult.HANDLED -> true
            KeyResult.CLOSE -> {
                onClose()
                true
            }
            KeyResult.PASS -> false
        }
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val box = session.focused?.box as? EditBoxTextBox
        if (box != null) return box.edit.charTyped(event)
        return session.charTyped(event.codepointAsString())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        currentEvent = event
        try {
            val handled = session.mouseClicked(event.x(), event.y(), MouseButtons.toProtocol(event.button()), mods(event), doubleClick)
            dragging = session.focused?.box as? EditBoxTextBox
            return handled
        } finally {
            currentEvent = null
        }
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging?.edit?.mouseReleased(event)
        dragging = null
        session.mouseReleased(event.x(), event.y(), MouseButtons.toProtocol(event.button()))
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val box = dragging
        if (box != null) return box.edit.mouseDragged(event, dx, dy)
        session.mouseDragged(event.x(), event.y(), MouseButtons.toProtocol(event.button()), dx, dy)
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean =
        session.mouseScrolled(x, y, scrollX, scrollY)

    override fun removed() {
        if (silent) session.dispose() else session.extension.screenRemoved(session)
    }

    override fun isPauseScreen(): Boolean = false

    // ---- ScreenView ---------------------------------------------------------------------------------------

    override fun createTextBox(onEdit: (String) -> Unit): TextBox = EditBoxTextBox(font, { currentEvent }, onEdit)
}
