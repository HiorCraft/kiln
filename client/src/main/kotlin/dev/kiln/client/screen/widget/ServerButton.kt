package dev.kiln.client.screen.widget

import dev.kiln.client.screen.MouseButtons
import dev.kiln.protocol.element.Button
import dev.kiln.protocol.message.ScreenClick
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component

/** Vanilla-looking button that reports every mouse button (and keyboard activation as left click) with modifiers. */
class ServerButton(private val env: WidgetEnv, private val elementId: String) : AbstractButton(0, 0, 0, 0, Component.empty()) {

    fun apply(e: Button, label: Component?) {
        // setMessage, not the field: WithInactiveMessage derives the greyed label of a disabled button there
        setMessage(label ?: Component.empty())
        active = e.enabled
    }

    override fun onPress(input: InputWithModifiers) {
        val button = if (input is MouseButtonEvent) MouseButtons.toProtocol(input.button()) else 0
        if (button >= 0) env.send(ScreenClick(env.screenId, elementId, button, Modifiers.of(input)))
    }

    override fun isValidClickButton(buttonInfo: MouseButtonInfo): Boolean = MouseButtons.toProtocol(buttonInfo.button()) >= 0

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(graphics)
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE))
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
