package dev.kiln.client.toast

import dev.kiln.client.screen.DisplayItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence

/**
 * Server toast (`ShowToast`): vanilla system-toast background, yellow title, white text, optional item icon at the
 * left (like advancement toasts). Height grows with the wrapped text; display time honours the player's
 * notification-time setting like vanilla toasts.
 */
class KilnToast(
    title: Component,
    text: Component?,
    private val icon: DisplayItem?,
    private val displayMillis: Long,
) : Toast {
    private val textX = if (icon != null) ICON_TEXT_X else PLAIN_TEXT_X
    private val titleLines: List<FormattedCharSequence>
    private val textLines: List<FormattedCharSequence>
    private val width: Int
    private var visibility = Toast.Visibility.SHOW

    init {
        val font = Minecraft.getInstance().font
        titleLines = font.split(title, MAX_TEXT_WIDTH).take(MAX_TITLE_LINES)
        textLines = text?.let { font.split(it, MAX_TEXT_WIDTH).take(MAX_TEXT_LINES) } ?: emptyList()
        val widest = (titleLines + textLines).maxOfOrNull(font::width) ?: 0
        width = maxOf(Toast.DEFAULT_WIDTH, textX + widest + 8)
    }

    override fun width(): Int = width

    override fun height(): Int {
        val lines = titleLines.size + textLines.size
        return maxOf(Toast.SLOT_HEIGHT, 10 + lines * LINE_HEIGHT)
    }

    override fun getWantedVisibility(): Toast.Visibility = visibility

    override fun update(manager: ToastManager, fullyVisibleForMs: Long) {
        val shownFor = displayMillis * manager.notificationDisplayTimeMultiplier
        visibility = if (fullyVisibleForMs < shownFor) Toast.Visibility.SHOW else Toast.Visibility.HIDE
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, font: Font, fullyVisibleForMs: Long) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height())
        val lines = titleLines.size + textLines.size
        var y = (height() - lines * LINE_HEIGHT) / 2 + 2
        for (line in titleLines) {
            graphics.text(font, line, textX, y, TITLE_COLOR, false)
            y += LINE_HEIGHT
        }
        for (line in textLines) {
            graphics.text(font, line, textX, y, TEXT_COLOR, false)
            y += LINE_HEIGHT
        }
        if (icon != null) {
            val iconY = (height() - 16) / 2
            graphics.fakeItem(icon.stack, 8, iconY)
            graphics.itemDecorations(font, icon.stack, 8, iconY, icon.countText)
        }
    }

    private companion object {
        val BACKGROUND_SPRITE: Identifier = Identifier.withDefaultNamespace("toast/system")
        const val PLAIN_TEXT_X = 18
        const val ICON_TEXT_X = 30
        const val MAX_TEXT_WIDTH = 200
        const val MAX_TITLE_LINES = 2
        const val MAX_TEXT_LINES = 5
        const val LINE_HEIGHT = 12
        const val TITLE_COLOR = 0xFFFFFF00.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
    }
}
