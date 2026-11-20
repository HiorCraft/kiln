package dev.kiln.client.ext.trust

import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.kiln.client.KilnClientMod
import dev.kiln.client.ext.Glyphs
import dev.kiln.client.ext.consent.ConsentListState
import dev.kiln.client.ext.consent.PermissionTexts
import dev.kiln.client.ext.handshake.ExtensionCache
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * "Server-Erweiterungen": every server with stored consent (`config/kiln-trust.json`), styled like the
 * [ConsentScreen].
 *
 * - Scrollable accordion ([ConsentListState]): header = chevron, address, number of extensions and a
 *   "Zurücknehmen" button; expanded = every extension with its id and all known permissions (green check = granted,
 *   red X = not granted).
 * - Revoking needs a second click on the same button ([ArmedConfirmation]); "Alle zurücknehmen" and "Cache leeren"
 *   at the bottom work the same way.
 * - Revoking the server the client is connected to unloads the extensions and disconnects ([TrustRevoker]).
 * - The list follows external changes of the trust file ([TrustStore.revision], checked every tick).
 *
 * Opened from the options screen (always) and the pause menu (on a kiln server), see [entryButton].
 */
class TrustManagementScreen(
    private val parent: Screen?,
    private val trust: TrustStore,
    private val cache: ExtensionCache,
    private val revoker: TrustRevoker,
    private val currentServer: () -> String?,
) : Screen(TITLE) {
    private val confirm = ArmedConfirmation<RevokeTarget>()
    private var entries: List<TrustEntry> = emptyList()
    private var list = ConsentListState(0)
    private var seenRevision = -1
    private var cacheBytes = 0L
    private var lastFrame = 0L

    private var listX = 0
    private var listY = 0
    private var listW = 0

    private var revokeAllButton: Button? = null
    private var clearCacheButton: Button? = null

    /** Set once the connection was dropped because of a revoke: the disconnect screen replaces this one. */
    private var disconnected = false

    override fun init() {
        listW = minOf(LIST_WIDTH, width - 24)
        listX = (width - listW) / 2
        listY = 42
        refresh(force = true)
        cacheBytes = cache.sizeBytes()

        revokeAllButton = addRenderableWidget(Button.builder(Component.literal("Alle zurücknehmen")) { press(RevokeTarget.All) }
            .bounds(width / 2 - 154, height - 52, 150, 20).build())
        clearCacheButton = addRenderableWidget(Button.builder(Component.literal("Cache leeren")) { press(RevokeTarget.Cache) }
            .bounds(width / 2 + 4, height - 52, 150, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Fertig")) { onClose() }.bounds(width / 2 - 100, height - 28, 200, 20).build())
        updateButtons(Util.getMillis())
    }

    /** Rebuilds the list from the store if it changed (keeps expanded entries and the scroll position). */
    private fun refresh(force: Boolean = false) {
        val servers = trust.servers() // reloads the file if it changed on disk
        if (!force && trust.revision == seenRevision) return
        seenRevision = trust.revision
        val expanded = entries.indices.filter { list.isExpanded(it) }.map { entries[it].server }.toSet()
        val scroll = list.scroll
        val newEntries = TrustEntry.of(servers)
        val newList = ConsentListState(newEntries.size, headerHeight = HEADER_HEIGHT, detailHeightOf = { TrustEntry.detailHeight(newEntries[it].grants.size) })
        newEntries.forEachIndexed { i, e -> if (e.server in expanded) newList.toggle(i) }
        // expanded entries show their chevron turned without animating again
        newList.animate(Float.MAX_VALUE)
        newList.setViewport((height - 70) - listY)
        newList.scrollBy(scroll)
        entries = newEntries
        list = newList
        val armed = confirm.armed(Util.getMillis())
        if (armed is RevokeTarget.Server && newEntries.none { it.server == armed.address }) confirm.disarm()
    }

    private fun press(target: RevokeTarget) {
        val now = Util.getMillis()
        if (!confirm.press(target, now)) {
            updateButtons(now)
            return
        }
        when (target) {
            is RevokeTarget.Server -> revoker.revoke(target.address)
            RevokeTarget.All -> revoker.revokeAll()
            RevokeTarget.Cache -> {
                cache.clear()
                cacheBytes = cache.sizeBytes()
            }
        }
        if (minecraft.gui.screen() !== this) {
            disconnected = true // revoked the current server: the disconnect screen took over
            return
        }
        refresh()
        updateButtons(now)
    }

    private fun updateButtons(now: Long) {
        val armed = confirm.armed(now)
        revokeAllButton?.let {
            it.active = entries.isNotEmpty()
            it.message = Component.literal(if (armed == RevokeTarget.All) "Wirklich alle?" else "Alle zurücknehmen")
        }
        clearCacheButton?.let {
            it.active = cacheBytes > 0
            it.message = Component.literal(
                if (armed == RevokeTarget.Cache) "Wirklich leeren?"
                else if (cacheBytes > 0) "Cache leeren (${Glyphs.formatBytes(cacheBytes)})" else "Cache leer",
            )
        }
    }

    override fun tick() {
        if (disconnected) return
        refresh()
        updateButtons(Util.getMillis())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        val now = Util.getMillis()
        list.animate(if (lastFrame == 0L) 0f else (now - lastFrame).toFloat())
        lastFrame = now

        graphics.centeredText(font, title, width / 2, 10, WHITE)
        val subtitle = when (entries.size) {
            0 -> "Keine gespeicherten Zustimmungen."
            1 -> "Gespeicherte Zustimmung für 1 Server:"
            else -> "Gespeicherte Zustimmungen für ${entries.size} Server:"
        }
        graphics.centeredText(font, subtitle, width / 2, 25, LIGHT)

        val bottom = listY + list.viewportHeight
        graphics.enableScissor(listX, listY, listX + listW, bottom)
        val inList = mouseX in listX until listX + listW && mouseY in listY until bottom
        val hoveredHeader = if (inList) list.headerAt((mouseY - listY).toDouble()) else -1
        val armed = confirm.armed(now)
        var pointing = false
        for ((i, entry) in entries.withIndex()) {
            val top = listY + list.entryTop(i) - list.scroll.toInt()
            if (top > bottom || top + list.entryHeight(i) < listY) continue
            val buttonHovered = i == hoveredHeader && revokeButtonContains(top, mouseX, mouseY)
            if (i == hoveredHeader) pointing = true
            drawEntry(graphics, i, entry, top, i == hoveredHeader && !buttonHovered, buttonHovered, armed == RevokeTarget.Server(entry.server))
        }
        graphics.disableScissor()
        if (pointing) graphics.requestCursor(CursorTypes.POINTING_HAND)

        if (list.maxScroll > 0) {
            val trackH = list.viewportHeight
            val thumbH = maxOf(16, trackH * trackH / list.contentHeight)
            val thumbY = listY + ((trackH - thumbH) * (list.scroll / list.maxScroll)).toInt()
            graphics.fill(listX + listW + 3, listY, listX + listW + 5, bottom, 0x40FFFFFF)
            graphics.fill(listX + listW + 3, thumbY, listX + listW + 5, thumbY + thumbH, 0xC0FFFFFF.toInt())
        }

        val hint = when {
            armed != null -> "Zum Bestätigen erneut klicken."
            entries.isEmpty() -> "Beim nächsten Verbinden fragt jeder Server wieder nach deiner Zustimmung."
            else -> "Zurückgenommen = beim nächsten Verbinden wird wieder gefragt."
        }
        graphics.centeredText(font, font.plainSubstrByWidth(hint, width - 8), width / 2, height - 64, if (armed != null) WARN else DIM)
    }

    private fun revokeButtonX(): Int = listX + listW - 22 - REVOKE_WIDTH

    private fun revokeButtonContains(top: Int, mouseX: Int, mouseY: Int): Boolean {
        val x = revokeButtonX()
        val y = top + (HEADER_HEIGHT - REVOKE_HEIGHT) / 2
        return mouseX >= x && mouseX < x + REVOKE_WIDTH && mouseY >= y && mouseY < y + REVOKE_HEIGHT
    }

    private fun drawEntry(graphics: GuiGraphicsExtractor, index: Int, entry: TrustEntry, top: Int, hovered: Boolean, buttonHovered: Boolean, armed: Boolean) {
        val h = HEADER_HEIGHT
        val x1 = listX + listW
        graphics.fill(listX, top, x1, top + list.entryHeight(index), 0x70000000)
        if (hovered) graphics.fill(listX, top, x1, top + h, 0x30FFFFFF)
        graphics.outline(listX, top, listW, list.entryHeight(index), if (list.isExpanded(index)) 0x60FFFFFF else 0x30FFFFFF)

        Glyphs.chevron(graphics, listX + 10f, top + h / 2f, list.chevron(index), if (hovered) WHITE else LIGHT)
        val textX = listX + 20
        val textW = revokeButtonX() - 6 - textX
        val connected = entry.server == currentServer()
        graphics.text(font, font.plainSubstrByWidth(entry.server, textW), textX, top + 4, WHITE, true)
        val count = entry.grants.size
        val info = (if (count == 1) "1 Erweiterung" else "$count Erweiterungen") + if (connected) " · verbunden" else ""
        graphics.text(font, font.plainSubstrByWidth(info, textW), textX, top + 15, if (connected) Glyphs.GREEN else DIM, false)

        // inline revoke button (vanilla buttons do not scroll with the list)
        val bx = revokeButtonX()
        val by = top + (h - REVOKE_HEIGHT) / 2
        val fill = when {
            armed -> if (buttonHovered) 0xFFB03A36.toInt() else 0xFF8E2C29.toInt()
            buttonHovered -> 0xFF4A515A.toInt()
            else -> 0xFF30353C.toInt()
        }
        graphics.fill(bx, by, bx + REVOKE_WIDTH, by + REVOKE_HEIGHT, fill)
        graphics.outline(bx, by, REVOKE_WIDTH, REVOKE_HEIGHT, if (buttonHovered || armed) 0xFFFFFFFF.toInt() else 0xFF6A727C.toInt())
        val label = if (armed) "Wirklich?" else "Zurücknehmen"
        graphics.text(font, label, bx + (REVOKE_WIDTH - font.width(label)) / 2, by + (REVOKE_HEIGHT - font.lineHeight) / 2 + 1, WHITE, true)
        if (!list.isExpanded(index)) return

        var y = top + h + 4
        for (grant in entry.grants) {
            graphics.text(font, font.plainSubstrByWidth(grant.id, listW - 16), listX + 8, y, WHITE, false)
            y += 13
            for (row in PermissionTexts.rows(grant.permissions)) {
                if (row.requested) Glyphs.check(graphics, listX + 14, y) else Glyphs.cross(graphics, listX + 14, y)
                graphics.text(font, row.label, listX + 28, y, if (row.requested) LIGHT else DIM, false)
                y += 12
            }
            y += 5
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        val x = event.x()
        val y = event.y()
        if (x >= listX && x < listX + listW && y >= listY && y < listY + list.viewportHeight) {
            val index = list.headerAt(y - listY)
            if (index >= 0) {
                val top = listY + list.entryTop(index) - list.scroll.toInt()
                if (revokeButtonContains(top, x.toInt(), y.toInt())) press(RevokeTarget.Server(entries[index].server))
                else list.toggle(index)
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

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    companion object {
        val TITLE: Component = Component.literal("Server-Erweiterungen")
        private const val LIST_WIDTH = 340
        private const val HEADER_HEIGHT = 28
        private const val REVOKE_WIDTH = 84
        private const val REVOKE_HEIGHT = 16
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val LIGHT = 0xFFC8CDD3.toInt()
        private const val DIM = 0xFF8A9099.toInt()
        private const val WARN = 0xFFFFB35A.toInt()

        /** Opens the screen for the running client (consent store, cache and connection of [KilnClientMod]). */
        @JvmStatic
        fun create(parent: Screen?): TrustManagementScreen {
            val client = KilnClientMod.client
            val revoker = TrustRevoker(KilnClientMod.trust, { client.currentServer }, client::disconnectAfterRevoke)
            return TrustManagementScreen(parent, KilnClientMod.trust, KilnClientMod.cache, revoker) { client.currentServer }
        }

        /** "Server-Erweiterungen…" button that opens this screen on top of [parent] (options screen, pause menu). */
        @JvmStatic
        fun entryButton(parent: Screen, width: Int): Button =
            Button.builder(Component.literal("Server-Erweiterungen…")) { Minecraft.getInstance().gui.setScreen(create(parent)) }
                .width(width).build()

        /** The pause menu shows the entry only while the client is in the world of a kiln server. */
        @JvmStatic
        fun showInPauseMenu(): Boolean = KilnClientMod.client.isInKilnWorld
    }
}
