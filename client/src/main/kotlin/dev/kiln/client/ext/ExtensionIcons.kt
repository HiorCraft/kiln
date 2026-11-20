package dev.kiln.client.ext

import com.mojang.blaze3d.platform.NativeImage
import dev.kiln.protocol.ExtensionInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * Icons of the announced extensions (`ExtensionInfo.icon`, PNG from the manifest) for the consent and loading
 * screens. Only this PNG is decoded before consent; size and dimensions are checked first. Registered as
 * `kiln:extension_icon/<id>`, released with [clear].
 */
class ExtensionIcons {
    private val logger = LoggerFactory.getLogger("kiln")
    private val icons = HashMap<String, Identifier?>()

    fun prepare(infos: List<ExtensionInfo>) {
        for (info in infos) {
            if (info.id in icons) continue
            icons[info.id] = info.icon?.let { register(info.id, it) }
        }
    }

    private fun register(id: String, png: ByteArray): Identifier? {
        val size = PngHeader.size(png)
        if (png.size > MAX_ICON_BYTES || size == null || size[0] !in 1..MAX_ICON_SIZE || size[1] !in 1..MAX_ICON_SIZE) {
            logger.warn("Ignoring invalid icon of extension {}", id)
            return null
        }
        return try {
            val image = NativeImage.read(png)
            val identifier = Identifier.fromNamespaceAndPath("kiln", "extension_icon/$id")
            Minecraft.getInstance().textureManager.register(identifier, DynamicTexture({ "kiln icon $id" }, image))
            identifier
        } catch (e: Exception) {
            logger.warn("Cannot decode icon of extension {}", id)
            null
        }
    }

    fun clear() {
        val textures = Minecraft.getInstance().textureManager
        icons.values.filterNotNull().forEach(textures::release)
        icons.clear()
    }

    /** Draws the icon of [id] (or a neutral default with the name's initial) at ([x], [y]), [size] square. */
    fun draw(graphics: GuiGraphicsExtractor, font: Font, id: String, name: String, x: Int, y: Int, size: Int) {
        val texture = icons[id]
        if (texture != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, size, size, size, size)
            return
        }
        graphics.fill(x, y, x + size, y + size, 0xFF3A4048.toInt())
        graphics.outline(x, y, size, size, 0xFF5E6772.toInt())
        graphics.fill(x + 2, y + 2, x + size - 2, y + 4, 0xFF4C545E.toInt())
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        graphics.text(font, initial, x + (size - font.width(initial)) / 2 + 1, y + (size - font.lineHeight) / 2 + 1, 0xFFD0D6DE.toInt(), true)
    }

    companion object {
        const val MAX_ICON_BYTES = 16 * 1024
        const val MAX_ICON_SIZE = 64
    }
}

/** Small pixel-art glyphs for the extension screens. */
object Glyphs {
    const val GREEN = 0xFF55E05A.toInt()
    const val RED = 0xFFFF5A55.toInt()

    /** Chevron (7 px tall) centered at ([cx], [cy]); [turn] 0 = pointing right, 1 = pointing down (rotated 90°). */
    fun chevron(graphics: GuiGraphicsExtractor, cx: Float, cy: Float, turn: Float, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(cx, cy)
        pose.rotate((Math.PI / 2 * turn).toFloat())
        for (col in 0 until 4) graphics.fill(-2 + col, -3 + col, -1 + col, 4 - col, color)
        pose.popMatrix()
    }

    /** Green check mark in an 8x8 box. */
    fun check(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val ys = intArrayOf(4, 5, 6, 5, 4, 3, 2, 1)
        for (i in ys.indices) graphics.fill(x + i, y + ys[i], x + i + 1, y + ys[i] + 2, GREEN)
    }

    /** Red X in an 8x8 box. */
    fun cross(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        for (i in 0 until 7) {
            graphics.fill(x + i, y + i, x + i + 1, y + i + 2, RED)
            graphics.fill(x + 6 - i, y + i, x + 7 - i, y + i + 2, RED)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.GERMANY, "%.2f MB", bytes / 1024.0 / 1024.0)
    }
}
