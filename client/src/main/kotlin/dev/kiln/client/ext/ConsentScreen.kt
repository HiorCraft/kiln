package dev.kiln.client.ext

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.protocol.ExtensionInfo
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * Consent prompt, shown in the configuration phase **before anything is downloaded** (only the manifest is known).
 *
 * - Scrollable accordion list ([ConsentListState]): collapsed row = icon, display name, chevron at the right that
 *   rotates when the entry opens; a click on the row toggles it. Expanded: version, id, every known permission
 *   with a green check (requested) or a red X (not requested) and the extensions it needs ("Benötigt:", protocol v5
 *   dependencies, with their names from the manifest).
 * - "Zulassen" / "Ablehnen und trennen" stay fixed at the bottom; Escape = decline. Exactly one answer counts
 *   ([ConsentDecision]).
 * - [tickConnection] keeps the pending connection ticking while this screen replaces the `ConnectScreen` (only that
 *   screen ticks it in the configuration phase); without it a server-side disconnect would go unnoticed.
 */
class ConsentScreen(
    private val server: String,
    private val infos: List<ExtensionInfo>,
    private val icons: ExtensionIcons,
    /** [Util.getMillis] time at which the server stops waiting. */
    private val deadlineMillis: Long,
    onAccept: () -> Unit,
    onDeny: () -> Unit,
    private val tickConnection: () -> Unit,
) : Screen(Component.literal("Server-Erweiterungen")) {
    private val decision = ConsentDecision(onAccept, onDeny)
    private val names = infos.associate { it.id to it.name }
    private val list = ConsentListState(
        infos.size,
        detailHeightOf = { 24 + dev.kiln.protocol.ExtensionPermissions.ALL.size * 12 + 4 + PermissionTexts.dependencyHeight(infos[it].dependencies.size) },
    )
    private var lastFrame = 0L

    private var listX = 0
    private var listY = 0
    private var listW = 0

    override fun init() {
        listW = minOf(LIST_WIDTH, width - 24)
        listX = (width - listW) / 2
        listY = 42
        list.setViewport((height - 62) - listY)
        val buttonsY = height - 26
        addRenderableWidget(Button.builder(Component.literal("Zulassen")) { decision.answer(true) }.bounds(width / 2 - 154, buttonsY, 150, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Ablehnen und trennen")) { decision.answer(false) }.bounds(width / 2 + 4, buttonsY, 150, 20).build())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val now = Util.getMillis()
        list.animate(if (lastFrame == 0L) 0f else (now - lastFrame).toFloat())
        lastFrame = now

        graphics.centeredText(font, title, width / 2, 10, WHITE)
        graphics.centeredText(font, "$server möchte folgende Erweiterungen laden:", width / 2, 25, LIGHT)

        val bottom = listY + list.viewportHeight
        graphics.enableScissor(listX, listY, listX + listW, bottom)
        val hoveredHeader = if (mouseX in listX until listX + listW) list.headerAt((mouseY - listY).toDouble()) else -1
        for ((i, info) in infos.withIndex()) {
            val top = listY + list.entryTop(i) - list.scroll.toInt()
            if (top > bottom || top + list.entryHeight(i) < listY) continue
            drawEntry(graphics, i, info, top, i == hoveredHeader)
        }
        graphics.disableScissor()
        if (hoveredHeader >= 0) graphics.requestCursor(CursorTypes.POINTING_HAND)

        if (list.maxScroll > 0) {
            val trackH = list.viewportHeight
            val thumbH = maxOf(16, trackH * trackH / list.contentHeight)
            val thumbY = listY + ((trackH - thumbH) * (list.scroll / list.maxScroll)).toInt()
            graphics.fill(listX + listW + 3, listY, listX + listW + 5, bottom, 0x40FFFFFF)
            graphics.fill(listX + listW + 3, thumbY, listX + listW + 5, thumbY + thumbH, 0xC0FFFFFF.toInt())
        }

        graphics.centeredText(font, font.plainSubstrByWidth("Skripte laufen in einer Sandbox: kein Zugriff auf Dateien oder Netzwerk.", width - 8), width / 2, height - 52, DIM)
        val seconds = ((deadlineMillis - now) / 1000).coerceAtLeast(0)
        graphics.centeredText(font, "Der Server wartet noch $seconds s auf deine Antwort.", width / 2, height - 40, DIM)
    }

    private fun drawEntry(graphics: GuiGraphicsExtractor, index: Int, info: ExtensionInfo, top: Int, hovered: Boolean) {
        val h = list.headerHeight
        val x1 = listX + listW
        graphics.fill(listX, top, x1, top + list.entryHeight(index), 0x70000000)
        if (hovered) graphics.fill(listX, top, x1, top + h, 0x30FFFFFF)
        graphics.outline(listX, top, listW, list.entryHeight(index), if (list.isExpanded(index)) 0x60FFFFFF else 0x30FFFFFF)
        icons.draw(graphics, font, info.id, info.name, listX + 3, top + 3, h - 6)
        val name = font.plainSubstrByWidth(info.name, listW - h - 24)
        graphics.text(font, name, listX + h + 4, top + (h - font.lineHeight) / 2 + 1, WHITE, true)
        Glyphs.chevron(graphics, x1 - 11f, top + h / 2f, list.chevron(index), if (hovered) WHITE else LIGHT)
        if (!list.isExpanded(index)) return

        var y = top + h + 2
        graphics.text(font, "Version: ${info.version}", listX + 8, y, LIGHT, false)
        y += 11
        graphics.text(font, "Interner Name: ${info.id}", listX + 8, y, LIGHT, false)
        y += 13
        for (row in PermissionTexts.rows(info.permissions)) {
            if (row.requested) Glyphs.check(graphics, listX + 10, y) else Glyphs.cross(graphics, listX + 10, y)
            graphics.text(font, row.label, listX + 24, y, if (row.requested) WHITE else DIM, false)
            y += 12
        }
        if (info.dependencies.isNotEmpty()) {
            y += 1
            graphics.text(font, "Benötigt:", listX + 8, y, LIGHT, false)
            y += 11
            for (label in PermissionTexts.dependencyLabels(info.dependencies, names)) {
                graphics.text(font, font.plainSubstrByWidth("• $label", listW - 28), listX + 16, y, WHITE, false)
                y += 11
            }
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.x() >= listX && event.x() < listX + listW) {
            val index = list.headerAt(event.y() - listY)
            if (index >= 0) {
                list.toggle(index)
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return false
        list.scrollBy(-scrollY * 14)
        return true
    }

    override fun tick() = tickConnection()

    override fun onClose() = decision.answer(false)

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val LIST_WIDTH = 340
        const val WHITE = 0xFFFFFFFF.toInt()
        const val LIGHT = 0xFFC8CDD3.toInt()
        const val DIM = 0xFF8A9099.toInt()
    }
}

/** The player's answer to a [ConsentScreen]: exactly one callback runs, once; later answers are ignored. */
class ConsentDecision(private val onAccept: () -> Unit, private val onDeny: () -> Unit) {
    var answered: Boolean = false
        private set

    fun answer(accepted: Boolean) {
        if (answered) return
        answered = true
        if (accepted) onAccept() else onDeny()
    }
}
