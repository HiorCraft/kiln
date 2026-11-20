package dev.kiln.client.ext

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.lua.DrawTarget
import dev.kiln.client.lua.ItemRef
import dev.kiln.client.screen.CountFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.ItemStack
import kotlin.math.atan2
import kotlin.math.sqrt

/** A decoded item for Lua ([ItemRef]): an immutable display copy of a vanilla stack (count 1). */
class StackItemRef(val stack: ItemStack) : ItemRef {
    override val id: String = BuiltInRegistries.ITEM.getKey(stack.item).toString()
    override val name: String = stack.hoverName.string
    override val maxStackSize: Int = stack.maxStackSize
}

/** [DrawTarget] on top of the 26.3 GUI extraction API, used for Lua widgets, HUD layers and Lua screens. */
class GuiDrawTarget(
    val graphics: GuiGraphicsExtractor,
    private val font: Font,
    private val textures: ExtensionTextures,
    private val mouseX: Int,
    private val mouseY: Int,
) : DrawTarget {

    override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) = graphics.fill(x0, y0, x1, y1, color)

    override fun gradient(x0: Int, y0: Int, x1: Int, y1: Int, top: Int, bottom: Int) = graphics.fillGradient(x0, y0, x1, y1, top, bottom)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.01f) return
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x1, y1)
        pose.rotate(atan2(dy, dx))
        pose.translate(0f, -thickness / 2f)
        pose.scale(length, thickness)
        graphics.fill(0, 0, 1, 1, color)
        pose.popMatrix()
    }

    override fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
        graphics.text(font, text, x, y, color, shadow)
    }

    override fun scaledText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x, y)
        pose.scale(scale, scale)
        graphics.text(font, text, 0, 0, color, shadow)
        pose.popMatrix()
    }

    override fun textWidth(text: String): Int = font.width(text)

    override fun wrap(text: String, width: Int): List<String> =
        font.splitter.splitLines(text, width, Style.EMPTY).map { it.string.trimEnd() }

    override fun textureSize(id: String): IntArray? = textures.size(id)

    override fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int) {
        val identifier = Identifier.tryParse(id) ?: return
        if (w <= 0 || h <= 0 || textureWidth <= 0 || textureHeight <= 0) return
        graphics.blit(RenderPipelines.GUI_TEXTURED, identifier, x, y, u, v, w, h, regionWidth, regionHeight, textureWidth, textureHeight)
    }

    override fun sprite(id: String, x: Int, y: Int, w: Int, h: Int) {
        val identifier = Identifier.tryParse(id) ?: return
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, identifier, x, y, w, h)
    }

    override fun item(itemId: String, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) {
        val stack = ItemCache.get(itemId) ?: return
        drawStack(stack, x, y, size, count, decorations)
    }

    override fun itemStack(ref: ItemRef, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) {
        val stack = (ref as? StackItemRef)?.stack ?: return
        drawStack(stack, x, y, size, count, decorations)
    }

    private fun drawStack(stack: ItemStack, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        if (size != 16) pose.scale(size / 16f, size / 16f)
        graphics.item(stack, 0, 0)
        if (decorations) graphics.itemDecorations(font, stack, 0, 0, CountFormat.decoration(count))
        pose.popMatrix()
    }

    override fun tooltip(text: String) {
        graphics.setTooltipForNextFrame(font, font.split(Component.literal(text), 200), mouseX, mouseY)
    }

    override fun tooltipLines(lines: List<String>) {
        val split = ArrayList<FormattedCharSequence>()
        for (line in lines) split += if (line.isEmpty()) listOf(FormattedCharSequence.EMPTY) else font.split(Component.literal(line), 240)
        if (split.isNotEmpty()) graphics.setTooltipForNextFrame(font, split, mouseX, mouseY)
    }

    override fun itemTooltip(ref: ItemRef, x: Int, y: Int, extra: List<String>) {
        val stack = (ref as? StackItemRef)?.stack ?: return
        val lines = ArrayList<FormattedCharSequence>()
        Screen.getTooltipFromItem(Minecraft.getInstance(), stack).mapTo(lines) { it.visualOrderText }
        for (line in extra) lines += Component.literal(line).visualOrderText
        graphics.setTooltipForNextFrame(
            font, lines, stack.tooltipImage, DefaultTooltipPositioner.INSTANCE, x, y, false, stack.get(DataComponents.TOOLTIP_STYLE),
        )
    }

    override fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int) = graphics.enableScissor(x0, y0, x1, y1)

    override fun popClip() = graphics.disableScissor()

    override fun cursor(name: String) {
        graphics.requestCursor(
            when (name) {
                "hand" -> CursorTypes.POINTING_HAND
                "text" -> CursorTypes.IBEAM
                "crosshair" -> CursorTypes.CROSSHAIR
                "resize_ns" -> CursorTypes.RESIZE_NS
                "resize_ew" -> CursorTypes.RESIZE_EW
                "move" -> CursorTypes.RESIZE_ALL
                "not_allowed" -> CursorTypes.NOT_ALLOWED
                else -> CursorTypes.ARROW
            },
        )
    }

    /** Item stacks by id string (immutable display copies). */
    private object ItemCache {
        private val stacks = HashMap<String, ItemStack?>()

        fun get(id: String): ItemStack? = stacks.getOrPut(id) {
            if (stacks.size > 512) stacks.clear()
            Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.let { ItemStack(it) }
        }
    }
}
