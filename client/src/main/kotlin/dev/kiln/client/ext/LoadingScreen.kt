package dev.kiln.client.ext

import dev.kiln.client.ext.ExtensionHandshake.Phase
import dev.kiln.client.ext.ExtensionHandshake.Status
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import kotlin.math.sin

/**
 * Full-screen loading screen while the extensions download, verify and start (configuration phase): server name,
 * overall progress (percent and bytes) with an animated bar, one row per extension (icon, name, status, own
 * progress), "Abbrechen" (disconnects). Shows "Fertig" at the end; the world screen replaces it when the server
 * finishes the configuration phase. Ticks the pending connection like [ConsentScreen].
 */
class LoadingScreen(
    private val server: String,
    private val handshake: ExtensionHandshake,
    private val icons: ExtensionIcons,
    private val tickConnection: () -> Unit,
) : Screen(Component.literal("Server-Erweiterungen werden geladen")) {
    private var shownProgress = 0f
    private var lastFrame = 0L
    private var cancel: Button? = null

    override fun init() {
        cancel = addRenderableWidget(Button.builder(Component.literal("Abbrechen")) { handshake.cancel() }.bounds(width / 2 - 75, height - 28, 150, 20).build())
    }

    override fun tick() {
        tickConnection()
        cancel?.active = handshake.phase != Phase.DONE && handshake.phase != Phase.FAILED
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.fillGradient(0, 0, width, height, 0xFF0D1117.toInt(), 0xFF1B2433.toInt())
        // slow diagonal light bands
        val t = Util.getMillis() / 40 % (width + height)
        for (i in 0 until 3) {
            val x = ((t + i * (width + height) / 3) % (width + height)).toInt() - height
            graphics.fillGradient(x, 0, x + 60, height, 0x00FFFFFF, 0x08FFFFFF)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val now = Util.getMillis()
        val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
        lastFrame = now
        shownProgress += (handshake.progress - shownProgress) * minOf(1f, dt * 6f)

        // server name, large
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(width / 2f, 18f)
        pose.scale(2f, 2f)
        val serverText = font.plainSubstrByWidth(server, width / 2 - 20)
        graphics.text(font, serverText, -font.width(serverText) / 2, 0, 0xFFFFFFFF.toInt(), true)
        pose.popMatrix()

        val status = when (handshake.phase) {
            Phase.DONE -> "Fertig"
            Phase.FAILED -> "Fehler: " + (handshake.error ?: "unbekannt")
            Phase.DECLINED -> "Abgebrochen"
            Phase.LOADING -> "Erweiterungen werden gestartet…"
            Phase.RELOADING -> "Ressourcen werden geladen…"
            else -> "Erweiterungen werden heruntergeladen…"
        }
        val statusColor = when (handshake.phase) {
            Phase.DONE -> Glyphs.GREEN
            Phase.FAILED -> Glyphs.RED
            else -> 0xFFC8CDD3.toInt()
        }
        graphics.centeredText(font, font.plainSubstrByWidth(status, width - 20), width / 2, 44, statusColor)

        // overall progress bar
        val barW = minOf(360, width - 40)
        val barX = (width - barW) / 2
        val barY = 60
        graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, 0xFF2A3342.toInt())
        val filled = (barW * shownProgress).toInt()
        graphics.fillGradient(barX, barY, barX + filled, barY + 6, 0xFF4FA3FF.toInt(), 0xFF2D6FD6.toInt())
        if (handshake.phase != Phase.DONE && handshake.phase != Phase.FAILED && filled > 8) {
            val shimmer = barX + ((now / 4) % (filled + 30)).toInt() - 30
            graphics.fill(maxOf(barX, shimmer), barY, minOf(barX + filled, shimmer + 30), barY + 6, 0x40FFFFFF)
        }
        val percent = (handshake.progress * 100).toInt()
        val bytes = "$percent %  ·  ${Glyphs.formatBytes(handshake.receivedBytes)} / ${Glyphs.formatBytes(handshake.totalBytes)}"
        graphics.centeredText(font, bytes, width / 2, barY + 11, 0xFF8A9099.toInt())

        // one row per extension, scrollable; follows the active row until the player scrolls
        val rowW = minOf(360, width - 40)
        val rowX = (width - rowW) / 2
        listTop = barY + 28
        listBottom = height - 34
        val items = handshake.items
        if (!manualScroll) {
            val active = items.indexOfFirst { it.status != Status.DONE && it.status != Status.CACHED }
            if (active >= 0) scroll = (active * STRIDE - (listBottom - listTop - ROW) / 2).toDouble()
        }
        clampScroll()
        graphics.enableScissor(rowX, listTop, rowX + rowW + SCROLLBAR, listBottom)
        for ((i, item) in items.withIndex()) {
            val y = listTop + i * STRIDE - scroll.toInt()
            if (y + ROW < listTop || y > listBottom) continue
            drawItem(graphics, item, rowX, y, rowW, now)
        }
        graphics.disableScissor()
        val content = contentHeight()
        val view = listBottom - listTop
        if (content > view) {
            val trackX = rowX + rowW + 2
            graphics.fill(trackX, listTop, trackX + 2, listBottom, 0x40FFFFFF)
            val thumbH = maxOf(12, view * view / content)
            val thumbY = listTop + ((view - thumbH) * (scroll / (content - view))).toInt()
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xC0FFFFFF.toInt())
        }
    }

    private var scroll = 0.0
    private var manualScroll = false
    private var listTop = 0
    private var listBottom = 0

    private fun contentHeight(): Int = (handshake.items.size * STRIDE - 2).coerceAtLeast(0)

    private fun clampScroll() {
        scroll = scroll.coerceIn(0.0, maxOf(0, contentHeight() - (listBottom - listTop)).toDouble())
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (y < listTop || y > listBottom) return super.mouseScrolled(x, y, scrollX, scrollY)
        manualScroll = true
        scroll -= scrollY * STRIDE
        clampScroll()
        return true
    }

    private fun drawItem(graphics: GuiGraphicsExtractor, item: ExtensionHandshake.Item, x: Int, y: Int, w: Int, now: Long) {
        graphics.fill(x, y, x + w, y + ROW, 0x50000000)
        icons.draw(graphics, font, item.info.id, item.info.name, x + 3, y + 3, ROW - 6)
        graphics.text(font, font.plainSubstrByWidth(item.info.name, w / 2), x + ROW + 2, y + (ROW - font.lineHeight) / 2 + 1, 0xFFFFFFFF.toInt(), true)
        val (text, color) = when (item.status) {
            Status.QUEUED -> "wartet" to 0xFF8A9099.toInt()
            Status.CACHED -> "aus dem Cache" to 0xFF9FC9FF.toInt()
            Status.DOWNLOADING -> "lädt ${if (item.info.size == 0) 0 else item.received * 100 / item.info.size} %" to 0xFF9FC9FF.toInt()
            Status.VERIFYING -> "prüft" to 0xFFE3C26B.toInt()
            Status.STARTING -> "startet" to 0xFFE3C26B.toInt()
            Status.DONE -> "fertig" to Glyphs.GREEN
            Status.FAILED -> "Fehler" to Glyphs.RED
        }
        val busy = item.status == Status.DOWNLOADING || item.status == Status.VERIFYING || item.status == Status.STARTING
        val pulse = if (busy) (0.6 + 0.4 * sin(now / 150.0)).toFloat() else 1f
        val shown = (color and 0x00FFFFFF) or ((255 * pulse).toInt().coerceIn(0, 255) shl 24)
        graphics.text(font, text, x + w - font.width(text) - 6, y + (ROW - font.lineHeight) / 2 + 1, shown, true)
        if (item.status == Status.DONE) Glyphs.check(graphics, x + w - font.width(text) - 18, y + (ROW - 8) / 2)
        if (item.status == Status.DOWNLOADING && item.info.size > 0) {
            val pw = (w - ROW - 4) * item.received / item.info.size
            graphics.fill(x + ROW + 2, y + ROW - 3, x + ROW + 2 + pw, y + ROW - 1, 0xFF4FA3FF.toInt())
        }
    }

    /** Escape does nothing here; cancelling is explicit (button). */
    override fun shouldCloseOnEsc(): Boolean = false

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val ROW = 22
        const val STRIDE = ROW + 2
        const val SCROLLBAR = 6
    }
}
