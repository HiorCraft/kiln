package dev.kiln.example.server.shop

import dev.kiln.server.api.screen.ButtonBuilder
import dev.kiln.server.api.screen.CheckboxStyle
import dev.kiln.server.api.screen.ContainerBuilder
import dev.kiln.server.api.screen.ModalBuilder
import dev.kiln.server.api.screen.ScreenBuilder
import dev.kiln.server.api.screen.ScreenItem
import dev.kiln.server.api.screen.TextAlign
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Renders the fullscreen shop from a [ShopSession] and the market. Pure view code: every handler only changes the
 * session (and refreshes) or calls an action on [ShopService].
 */
class ShopScreen(private val service: ShopService) {
    private val market get() = service.market

    fun render(b: ScreenBuilder, player: Player, s: ShopSession) {
        val now = System.currentTimeMillis()
        b.background = BG
        header(b, player, s)
        val top = HEADER_H + PAD
        val height = b.height - top - PAD
        when (s.tab) {
            ShopTab.MARKET -> {
                marketTab(b, player, s, top, height, now)
                buyModals(b, player, s, now)
            }
            ShopTab.SELL -> {
                sellTab(b, player, s, top, height)
                sellConfirmModal(b, player, s)
            }
            ShopTab.MY_OFFERS -> {
                myOffersTab(b, player, s, top, height, now)
                withdrawModal(b, player, s)
                priceEditModal(b, player, s, now)
            }
        }
    }

    // ---- header ---------------------------------------------------------------------------------------------

    private fun header(b: ScreenBuilder, player: Player, s: ShopSession) {
        val width = b.width
        val showTitle = width >= MIN_WIDTH_FOR_TITLE
        val balanceText = ShopFormat.money(market.balance(player.uniqueId))
        b.panel(0, 0, width, HEADER_H, id = "header") {
            background = HEADER_BG
            if (showTitle) {
                text(Component.text("Marktplatz", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), x = PAD, y = 9, width = 76, id = "title")
            }
            var x = if (showTitle) 86 else PAD
            for (tab in ShopTab.entries) {
                val tabWidth = ShopFormat.width(tab.label) + 16
                tabButton(this, s, tab, x, tabWidth)
                x += tabWidth + 4
            }
            val balanceWidth = width - x - PAD
            text(
                ShopFormat.ellipsize(balanceText, balanceWidth), x = x, y = 9, width = balanceWidth, align = TextAlign.RIGHT,
                color = GOLD, id = "balance", tooltip = Component.text("Dein Kontostand (Demo-Geld, Start ${ShopFormat.money(Market.START_BALANCE)})"),
            )
        }
    }

    private fun tabButton(c: ContainerBuilder, s: ShopSession, tab: ShopTab, x: Int, width: Int) {
        val selectedTab = s.tab == tab
        val id = "tab/${tab.name.lowercase()}"
        c.panel(x, 4, width, HEADER_H - 8, id = id) {
            background = TAB_BG
            hoverBackground = TAB_HOVER
            selectedBackground = TAB_SELECTED
            selected = selectedTab
            border = if (selectedTab) GOLD else null
            tooltip = Component.text(tab.hint)
            text(
                tab.label, y = (HEADER_H - 8 - 8) / 2, width = width, align = TextAlign.CENTER,
                color = if (selectedTab) Color.WHITE else GRAY, id = "$id/label",
            )
            onClick { ctx ->
                s.tab = tab
                ctx.screen.refresh()
            }
        }
    }

    // ---- market ---------------------------------------------------------------------------------------------

    private fun marketTab(b: ScreenBuilder, player: Player, s: ShopSession, top: Int, height: Int, now: Long) {
        val offers = market.query(MarketQuery(s.category, s.search, s.sort), now)
        categorySidebar(b, s, top, height, now)
        val x = PAD + SIDEBAR_W + PAD
        val width = b.width - x - PAD
        searchBar(b, s, x, top, width)
        b.text("${offerCount(offers.size)} · ${s.category.label}", x, top + 23, width, color = DARK_GRAY, id = "result_count")
        offerGrid(b, player, s, offers, x, top + 34, width, height - 34)
    }

    private fun categorySidebar(b: ScreenBuilder, s: ShopSession, top: Int, height: Int, now: Long) {
        b.panel(PAD, top, SIDEBAR_W, height, id = "categories") {
            background = PANEL_BG
            text("Kategorien", x = 4, y = 4, width = SIDEBAR_W - 8, color = GRAY, id = "categories/title")
            var y = 16
            for (category in Category.entries) {
                val count = market.query(MarketQuery(category, s.search, s.sort), now).size
                categoryRow(this, s, category, count, y)
                y += 18
            }
        }
    }

    private fun categoryRow(c: ContainerBuilder, s: ShopSession, category: Category, count: Int, y: Int) {
        val selectedCategory = s.category == category
        val id = "category/${category.name.lowercase()}"
        c.panel(2, y, SIDEBAR_W - 4, 16, id = id) {
            background = PANEL_BG
            hoverBackground = TILE_HOVER
            selectedBackground = TAB_SELECTED
            selected = selectedCategory
            tooltip = Component.text("$count passende Angebote")
            text(category.label, x = 4, y = 4, width = SIDEBAR_W - 30, color = if (selectedCategory) Color.WHITE else GRAY, id = "$id/label")
            text(count.toString(), x = SIDEBAR_W - 26, y = 4, width = 18, align = TextAlign.RIGHT, color = DARK_GRAY, id = "$id/count")
            onClick { ctx ->
                s.category = category
                ctx.screen.refresh()
            }
        }
    }

    /** Search field (with a clear button while searching) and the sort dropdown, right-aligned in [width]. */
    private fun searchBar(b: ScreenBuilder, s: ShopSession, x: Int, top: Int, width: Int) {
        val sortWidth = 92
        val clearWidth = if (s.search.isNotEmpty()) 18 else 0
        val searchWidth = (width - sortWidth - 4 - clearWidth).coerceAtLeast(40)
        b.textField("search", x, top, searchWidth, 18) {
            value = s.search
            maxLength = 48
            placeholder = Component.text("Suchen (Name, Item, Verkäufer) ...", NamedTextColor.DARK_GRAY)
            tooltip = Component.text("Filtert live; mehrere Wörter müssen alle vorkommen")
            onInput { ctx ->
                s.search = ctx.value
                ctx.screen.refresh()
            }
        }
        if (clearWidth > 0) {
            b.button("search_clear", "x", x + searchWidth + 2, top, 16, 18) {
                tooltip = Component.text("Suche leeren")
                onClick { ctx ->
                    s.search = ""
                    ctx.screen.refresh()
                }
            }
        }
        b.dropdown("sort", x + width - sortWidth, top, sortWidth, 18) {
            for (order in SortOrder.entries) option(order.name, order.label)
            selected = s.sort.name
            tooltip = Component.text("Sortierung")
            onChange { ctx ->
                s.sort = SortOrder.valueOf(ctx.value)
                ctx.screen.refresh()
            }
        }
    }

    private fun offerGrid(
        b: ScreenBuilder, player: Player, s: ShopSession, offers: List<Offer<ItemStack>>, x: Int, y: Int, width: Int, height: Int,
    ) {
        val innerWidth = width - SCROLLBAR_W - 2 * TILE_PAD
        val columns = ShopLayout.columns(innerWidth, TILE_MIN_W, TILE_GAP)
        val tileWidth = ShopLayout.tileWidth(innerWidth, columns, TILE_GAP)
        b.scrollPanel("offers", x, y, width, height) {
            background = PANEL_BG
            if (offers.isEmpty()) {
                text("Keine Angebote gefunden", y = 24, width = width - SCROLLBAR_W, align = TextAlign.CENTER, color = GRAY, id = "offers/empty")
                text(
                    "Andere Suche oder Kategorie probieren.", y = 36, width = width - SCROLLBAR_W, align = TextAlign.CENTER,
                    color = DARK_GRAY, id = "offers/empty_hint",
                )
                return@scrollPanel
            }
            for ((index, offer) in offers.withIndex()) {
                val tileX = TILE_PAD + index % columns * (tileWidth + TILE_GAP)
                val tileY = TILE_PAD + index / columns * (TILE_H + TILE_GAP)
                offerTile(this, player, s, offer, tileX, tileY, tileWidth)
            }
        }
    }

    private fun offerTile(c: ContainerBuilder, player: Player, s: ShopSession, offer: Offer<ItemStack>, x: Int, y: Int, width: Int) {
        val own = offer.sellerId == player.uniqueId
        val textWidth = (width - 34).coerceAtLeast(1)
        c.panel(x, y, width, TILE_H, id = "offer/${offer.id}") {
            background = TILE_BG
            hoverBackground = TILE_HOVER
            border = if (own) AQUA_DIM else null
            tooltip = Component.text(if (own) "Dein eigenes Angebot" else "Klicken zum Kaufen", NamedTextColor.GRAY)
            item(
                ScreenItem.of(offer.item, offer.amount), x = 4, y = (TILE_H - 24) / 2, size = 24,
                tooltip = Component.text("${ShopFormat.money(offer.pricePerUnit)} pro Stück · Klicken zum Kaufen", NamedTextColor.GOLD),
                id = "offer/${offer.id}/item",
            )
            text(ShopFormat.ellipsize(offer.name, textWidth), x = 32, y = 4, width = textWidth, id = "offer/${offer.id}/name")
            text(ShopFormat.ellipsize("${ShopFormat.money(offer.pricePerUnit)}/Stk.", textWidth), x = 32, y = 14, width = textWidth, color = GOLD, id = "offer/${offer.id}/price")
            text(
                ShopFormat.ellipsize(if (own) "von dir" else "von ${offer.sellerName}", textWidth), x = 32, y = 24, width = textWidth,
                color = if (own) AQUA else GRAY, id = "offer/${offer.id}/seller",
            )
            text(
                ShopFormat.ellipsize("Verfügbar: ${ShopFormat.number(offer.amount.toLong())}", textWidth), x = 32, y = 34, width = textWidth,
                color = DARK_GRAY, id = "offer/${offer.id}/amount",
            )
            onClick { ctx ->
                s.openBuyDialog(offer.id, offer.pricePerUnit)
                ctx.screen.refresh()
            }
        }
    }

    /** The buy dialog of the chosen offer (or a notice that it is gone) and, on top, its confirmation. */
    private fun buyModals(b: ScreenBuilder, player: Player, s: ShopSession, now: Long) {
        val offerId = s.buyOfferId ?: return
        val modalWidth = minOf(320, b.width - 16)
        val offer = market.offer(offerId)?.takeIf { it.isActive(now) }
        if (offer == null) {
            offerGoneModal(b, s, modalWidth)
            return
        }
        s.buyAmount = s.buyAmount.coerceIn(1, offer.amount)
        val total = Market.totalPrice(offer.pricePerUnit, s.buyAmount)
        buyModal(b, player, s, offer, total, modalWidth)
        if (s.buyConfirm) buyConfirmModal(b, s, offer, total)
    }

    private fun offerGoneModal(b: ScreenBuilder, s: ShopSession, modalWidth: Int) {
        b.modal("buy", modalWidth, 64) {
            text("Dieses Angebot ist nicht mehr verfügbar.", x = 10, y = 12, width = modalWidth - 20, align = TextAlign.CENTER, color = RED, id = "buy/gone")
            backButton("buy_close", "Schließen", (modalWidth - 80) / 2, 34, 80) { s.closeBuyDialog() }
        }
    }

    private fun buyModal(b: ScreenBuilder, player: Player, s: ShopSession, offer: Offer<ItemStack>, total: Long, modalWidth: Int) {
        val available = offer.amount
        val amount = s.buyAmount
        val balance = market.balance(player.uniqueId)
        val own = offer.sellerId == player.uniqueId
        val affordable = total <= balance
        val priceChanged = offer.pricePerUnit != s.buyPriceSeen
        val modalHeight = minOf(196, b.height - 8)
        val inner = modalWidth - 20

        b.modal("buy", modalWidth, modalHeight) {
            item(ScreenItem.of(offer.item, 1), x = 10, y = 10, size = 32, id = "buy/item")
            text(ShopFormat.ellipsize(offer.name, inner - 40), x = 50, y = 10, width = inner - 40, id = "buy/name")
            text(
                ShopFormat.ellipsize("${ShopFormat.money(offer.pricePerUnit)}/Stk. · von ${if (own) "dir" else offer.sellerName}", inner - 40),
                x = 50, y = 22, width = inner - 40, color = GOLD, id = "buy/price",
            )
            text("Verfügbar: ${ShopFormat.number(available.toLong())}", x = 50, y = 33, width = inner - 40, color = GRAY, id = "buy/available")

            text("Menge", x = 10, y = 50, width = 60, color = GRAY, id = "buy/amount_label")
            amountInputs(this, s, available, own, inner)
            if (s.buyAmountInvalid) {
                text("Bitte eine Menge von 1 bis $available eingeben.", x = 10, y = 84, width = inner, color = RED, id = "buy/amount_error")
            }
            text("Gesamtpreis: ${ShopFormat.money(total)}", x = 10, y = 96, width = inner, color = GOLD, id = "buy/total")
            text(
                if (affordable) "Kontostand danach: ${ShopFormat.money(balance - total)}" else "Nicht genug Geld: es fehlen ${ShopFormat.money(total - balance)}",
                x = 10, y = 107, width = inner, color = if (affordable) GRAY else RED, id = "buy/after",
            )
            if (priceChanged) {
                text("Achtung: Der Preis wurde gerade geändert.", x = 10, y = 118, width = inner, color = YELLOW, id = "buy/price_changed")
            }
            shulkerOption(this, s, own, inner)
            val error = if (own) "Das ist dein eigenes Angebot." else s.buyError
            if (error != null) {
                text(error, x = 10, y = 157, width = inner, height = 9, color = RED, id = "buy/error")
            }
            backButton("buy_cancel", "Abbrechen", modalWidth - 90, modalHeight - 28, 80) { s.closeBuyDialog() }
            button("buy_ok", "Kaufen", modalWidth - 176, modalHeight - 28, 80, 20) {
                enabled = !own && affordable && !s.buyAmountInvalid
                tooltip = Component.text(
                    when {
                        own -> "Eigene Angebote kannst du unter „Meine Angebote“ zurückziehen."
                        !affordable -> "Zu wenig Geld"
                        s.buyAmountInvalid -> "Ungültige Menge"
                        else -> "$amount Stück für ${ShopFormat.money(total)} kaufen"
                    },
                )
                onClick { ctx -> service.requestBuy(ctx.player, s) }
            }
        }
    }

    /** Slider and text field for the amount to buy; both write [ShopSession.buyAmount]. */
    private fun amountInputs(c: ContainerBuilder, s: ShopSession, available: Int, own: Boolean, inner: Int) {
        c.slider("buy_slider", 10, 60, inner - 76, 20) {
            min = 1.0
            max = available.toDouble()
            step = 1.0
            value = s.buyAmount.toDouble()
            label = Component.text("Menge")
            enabled = available > 1 && !own
            tooltip = Component.text("Ziehen, um die Menge zu wählen (1 bis $available)")
            onChange { ctx ->
                ctx.int?.let {
                    s.buyAmount = it.coerceIn(1, available)
                    s.buyAmountInvalid = false
                    s.buyError = null
                }
                ctx.screen.refresh()
            }
        }
        c.textField("buy_amount", 10 + inner - 70, 60, 70, 20) {
            value = s.buyAmount.toString()
            maxLength = 10
            numeric(1.0, available.toDouble())
            tooltip = Component.text("Menge eintippen (1 bis $available)")
            onInput { ctx ->
                val number = ctx.int
                if (number == null || number !in 1..available) {
                    s.buyAmountInvalid = true
                } else {
                    s.buyAmount = number
                    s.buyAmountInvalid = false
                }
                s.buyError = null
                ctx.screen.refresh()
            }
        }
    }

    private fun shulkerOption(c: ContainerBuilder, s: ShopSession, own: Boolean, inner: Int) {
        c.checkbox("buy_shulker", 10, 130, inner, 14) {
            checked = s.buyShulker
            label = Component.text("In Shulker-Box liefern")
            enabled = !own
            tooltip = Component.text("Demo: Die Option wird angezeigt, die Lieferung erfolgt aber lose ins Inventar.")
            onChange { ctx ->
                s.buyShulker = ctx.checked
                ctx.screen.refresh()
            }
        }
        if (s.buyShulker) {
            c.text("Demo: Items kommen trotzdem lose ins Inventar.", x = 10, y = 146, width = inner, color = DARK_GRAY, id = "buy/shulker_hint")
        }
    }

    private fun buyConfirmModal(b: ScreenBuilder, s: ShopSession, offer: Offer<ItemStack>, total: Long) {
        val w = dialogWidth(b, 260)
        b.modal("buy_confirm", w, 84) {
            text(
                textAroundItemName("Wirklich ${s.buyAmount}× ", offer.item, " für ${ShopFormat.money(total)} kaufen?"),
                x = 10, y = 12, width = w - 20, height = 30, align = TextAlign.CENTER, id = "buy_confirm/text",
            )
            confirmButtons(
                w, 54, "buy_confirm_yes", "Ja, kaufen", "buy_confirm_no", "Zurück",
                confirm = { onClick { ctx -> service.confirmBuy(ctx.player, s) } },
                back = { s.buyConfirm = false },
            )
        }
    }

    // ---- sell -----------------------------------------------------------------------------------------------

    private fun sellTab(b: ScreenBuilder, player: Player, s: ShopSession, top: Int, height: Int) {
        s.inventorySignature = service.inventorySignature(player)
        inventoryColumn(b, player, s, top)
        val formX = PAD + INVENTORY_W + 12
        sellForm(b, player, s, formX, top, b.width - formX - PAD, height)
    }

    private fun inventoryColumn(b: ScreenBuilder, player: Player, s: ShopSession, top: Int) {
        b.text("Dein Inventar", PAD, top, INVENTORY_W, color = GRAY, id = "sell/inventory_title")
        b.inventoryGrid(player, PAD, top + 12, idPrefix = "inv") {
            slots { slot, item ->
                enabled = item != null
                selected = slot == s.sellSlot
                tooltip = if (item != null) Component.text("Klick: auswählen · Shift-Klick: ganzer Stack", NamedTextColor.GRAY) else null
            }
            onClick { ctx -> service.selectSellSlot(ctx.player, s, ctx.slot, ctx.shift) }
        }
        b.text(
            "Klick wählt ein Item, Shift-Klick den ganzen Stack.", PAD, top + 12 + INVENTORY_H + 6, INVENTORY_W, height = 20,
            color = DARK_GRAY, id = "sell/inventory_hint",
        )
    }

    private fun sellForm(b: ScreenBuilder, player: Player, s: ShopSession, formX: Int, top: Int, formWidth: Int, height: Int) {
        val template = s.sellTemplate
        val available = template?.let { service.countInInventory(player, it) } ?: 0
        val validation = service.validateListing(player, s)
        val errors = if (s.sellSubmitted) validation.errors else validation.errors.filterKeys { it == "amount" && s.sellAmount.isNotBlank() }
        val inner = (formWidth - SCROLLBAR_W - 8).coerceAtLeast(80)
        val labelWidth = 70
        val inputX = 4 + labelWidth
        val inputWidth = (inner - labelWidth).coerceAtLeast(40)

        b.scrollPanel("sell_form", formX, top, formWidth, height) {
            background = PANEL_BG
            var y = 4
            text(Component.text("Neues Angebot", NamedTextColor.GOLD).decorate(TextDecoration.BOLD), x = 4, y = y, width = inner, id = "sell/title")
            y += 14

            chosenItem(this, s, template, available, y, inner)
            y += 20
            y = errorLine(this, errors["item"], "sell/item_error", y, inner)

            text("Menge", x = 4, y = y + 5, width = labelWidth, color = GRAY, id = "sell/amount_label")
            textField("sell_amount", inputX, y, inputWidth, 18) {
                value = s.sellAmount
                maxLength = 10
                numeric(1.0, maxOf(1, available).toDouble())
                placeholder = Component.text("Anzahl", NamedTextColor.DARK_GRAY)
                tooltip = Component.text(if (template != null) "1 bis $available" else "Erst ein Item wählen")
                onInput { ctx ->
                    s.sellAmount = ctx.value
                    ctx.screen.refresh()
                }
            }
            y += 20
            y = errorLine(this, errors["amount"], "sell/amount_error", y, inner)

            text("Preis/Stk.", x = 4, y = y + 5, width = labelWidth, color = GRAY, id = "sell/price_label")
            textField("sell_price", inputX, y, inputWidth, 18) {
                value = s.sellPrice
                maxLength = 10
                numeric(1.0, Market.MAX_PRICE.toDouble())
                placeholder = Component.text("Taler", NamedTextColor.DARK_GRAY)
                tooltip = Component.text("Preis pro Stück in Taler (1 bis ${ShopFormat.number(Market.MAX_PRICE)})")
                onInput { ctx ->
                    s.sellPrice = ctx.value
                    ctx.screen.refresh()
                }
            }
            y += 20
            y = errorLine(this, errors["price"], "sell/price_error", y, inner)
            val totalValue = validation.amount?.let { a -> validation.price?.let { p -> Market.totalPrice(p, a) } }
            text(
                "Gesamtwert: ${totalValue?.let { ShopFormat.money(it) } ?: "-"}", x = inputX, y = y, width = inputWidth,
                color = GOLD, id = "sell/total",
            )
            y += 12

            text("Laufzeit", x = 4, y = y + 5, width = labelWidth, color = GRAY, id = "sell/duration_label")
            dropdown("sell_duration", inputX, y, inputWidth, 18) {
                for (duration in ListingDuration.entries) option(duration.name, duration.label)
                selected = s.sellDuration.name
                tooltip = Component.text("Wie lange das Angebot im Markt steht")
                onChange { ctx ->
                    s.sellDuration = ListingDuration.valueOf(ctx.value)
                    ctx.screen.refresh()
                }
            }
            y += 22

            text("Kategorie", x = 4, y = y + 5, width = labelWidth, color = GRAY, id = "sell/category_label")
            dropdown("sell_category", inputX, y, inputWidth, 18) {
                for (category in Category.listable) option(category.name, category.label)
                selected = s.sellCategory?.name
                placeholder = Component.text("Kategorie wählen", NamedTextColor.DARK_GRAY)
                tooltip = Component.text("Wird beim Auswählen eines Items vorgeschlagen")
                onChange { ctx ->
                    s.sellCategory = Category.valueOf(ctx.value)
                    ctx.screen.refresh()
                }
            }
            y += 20
            y = errorLine(this, errors["category"], "sell/category_error", y, inner)

            checkbox("sell_notify", 4, y + 2, inner, 16) {
                checked = s.sellNotify
                style = CheckboxStyle.TOGGLE
                label = Component.text("Benachrichtigen bei Verkauf")
                tooltip = Component.text("Toast und Chat-Nachricht, wenn jemand kauft (nur wenn du online bist)")
                onChange { ctx ->
                    s.sellNotify = ctx.checked
                    ctx.screen.refresh()
                }
            }
            y += 22
            y = errorLine(this, s.sellError, "sell/error", y, inner)

            button("sell_submit", "Angebot einstellen", 4, y, inner, 20) {
                enabled = template != null
                tooltip = Component.text(if (template != null) "Prüfen und bestätigen" else "Erst ein Item wählen")
                onClick { ctx -> service.requestListing(ctx.player, s) }
            }
        }
    }

    /** The item chosen to sell (click to deselect), its name and how many the player has. */
    private fun chosenItem(c: ContainerBuilder, s: ShopSession, template: ItemStack?, available: Int, y: Int, inner: Int) {
        c.itemSlot("sell_item", 4, y) {
            item(template?.let { it.asQuantity(1) }, available.takeIf { it > 0 })
            placeholderSprite = null
            tooltip = Component.text(if (template != null) "Klicken zum Abwählen" else "Wähle links ein Item", NamedTextColor.GRAY)
            onClick { ctx ->
                s.clearSellForm()
                ctx.screen.refresh()
            }
        }
        c.text(
            ShopFormat.ellipsize(template?.let { service.plainName(it) } ?: "Kein Item gewählt", inner - 26),
            x = 26, y = y + 1, width = inner - 26, color = if (template != null) Color.WHITE else DARK_GRAY, id = "sell/item_name",
        )
        c.text(
            if (template != null) "Im Inventar: ${ShopFormat.number(available.toLong())}" else "Links im Inventar anklicken",
            x = 26, y = y + 10, width = inner - 26, color = GRAY, id = "sell/item_count",
        )
    }

    /** Red validation text (or nothing); returns the next y. */
    private fun errorLine(c: ContainerBuilder, error: String?, id: String, y: Int, width: Int): Int {
        if (error == null) return y
        c.text(error, x = 4, y = y, width = width, color = RED, id = id)
        return y + 11
    }

    private fun sellConfirmModal(b: ScreenBuilder, player: Player, s: ShopSession) {
        if (!s.sellConfirm) return
        val template = s.sellTemplate ?: return
        val validation = service.validateListing(player, s)
        val amount = validation.amount ?: return
        val price = validation.price ?: return
        val w = dialogWidth(b, 280)
        b.modal("sell_confirm", w, 106) {
            text(
                textAroundItemName(
                    "${amount}× ", template,
                    " für ${ShopFormat.money(price)}/Stk. (gesamt ${ShopFormat.money(Market.totalPrice(price, amount))}) für ${s.sellDuration.label} anbieten?",
                ),
                x = 10, y = 10, width = w - 20, height = 40, align = TextAlign.CENTER, id = "sell_confirm/text",
            )
            text("Die Items werden aus deinem Inventar genommen.", x = 10, y = 54, width = w - 20, align = TextAlign.CENTER, color = GRAY, id = "sell_confirm/hint")
            confirmButtons(
                w, 76, "sell_confirm_yes", "Einstellen", "sell_confirm_no", "Zurück",
                confirm = { onClick { ctx -> service.confirmListing(ctx.player, s) } },
                back = { s.sellConfirm = false },
            )
        }
    }

    // ---- my offers ------------------------------------------------------------------------------------------

    private fun myOffersTab(b: ScreenBuilder, player: Player, s: ShopSession, top: Int, height: Int, now: Long) {
        val own = market.offersOf(player.uniqueId)
        val width = b.width - 2 * PAD
        val stock = own.filter { it.isActive(now) }.sumOf { Market.totalPrice(it.pricePerUnit, it.amount) }
        b.text("${offerCount(own.size)} · Warenwert im Markt: ${ShopFormat.money(stock)}", PAD, top, width, color = GRAY, id = "my/summary")
        val rowWidth = width - SCROLLBAR_W - 8
        b.scrollPanel("my_offers", PAD, top + 12, width, height - 12) {
            background = PANEL_BG
            if (own.isEmpty()) {
                text("Du hast keine Angebote.", y = 24, width = width - SCROLLBAR_W, align = TextAlign.CENTER, color = GRAY, id = "my/empty")
                button("my/go_sell", "Jetzt etwas verkaufen", (width - SCROLLBAR_W - 140) / 2, 40, 140, 20) {
                    onClick { ctx ->
                        s.tab = ShopTab.SELL
                        ctx.screen.refresh()
                    }
                }
                return@scrollPanel
            }
            for ((index, offer) in own.withIndex()) myOfferRow(this, s, offer, 4 + index * (MY_ROW_H + 3), rowWidth, now)
        }
    }

    private fun myOfferRow(c: ContainerBuilder, s: ShopSession, offer: Offer<ItemStack>, y: Int, rowWidth: Int, now: Long) {
        val expired = offer.isExpired(now)
        val textWidth = (rowWidth - 30 - 2 * 82).coerceAtLeast(1)
        c.panel(4, y, rowWidth, MY_ROW_H, id = "my/${offer.id}") {
            background = TILE_BG
            border = if (expired) RED_DIM else null
            item(ScreenItem.of(offer.item, offer.amount), x = 6, y = (MY_ROW_H - 16) / 2, id = "my/${offer.id}/item")
            text(ShopFormat.ellipsize(offer.name, textWidth), x = 28, y = 5, width = textWidth, id = "my/${offer.id}/name")
            text(
                ShopFormat.ellipsize(
                    if (expired) "abgelaufen · nicht mehr im Markt"
                    else "${ShopFormat.money(offer.pricePerUnit)}/Stk. · ${offer.category.label} · noch ${ShopFormat.remaining(offer.expiresAt, now)}",
                    textWidth,
                ),
                x = 28, y = 16, width = textWidth, color = if (expired) RED else GRAY, id = "my/${offer.id}/info",
            )
            button("my/${offer.id}/price", "Preis ändern", rowWidth - 164, 5, 78, 20) {
                enabled = !expired
                tooltip = Component.text(if (expired) "Abgelaufene Angebote nur zurückziehen" else "Aktuell ${ShopFormat.money(offer.pricePerUnit)}/Stk.")
                onClick { ctx ->
                    s.closeMyOfferDialogs()
                    s.priceEditOfferId = offer.id
                    s.priceEditText = offer.pricePerUnit.toString()
                    ctx.screen.refresh()
                }
            }
            button("my/${offer.id}/withdraw", "Zurückziehen", rowWidth - 82, 5, 78, 20) {
                tooltip = Component.text("Items zurück ins Inventar")
                onClick { ctx ->
                    s.closeMyOfferDialogs()
                    s.withdrawOfferId = offer.id
                    ctx.screen.refresh()
                }
            }
        }
    }

    private fun withdrawModal(b: ScreenBuilder, player: Player, s: ShopSession) {
        val id = s.withdrawOfferId ?: return
        val offer = market.offer(id)?.takeIf { it.sellerId == player.uniqueId }
        if (offer == null) {
            s.withdrawOfferId = null
            return
        }
        val space = service.freeSpace(player, offer.item)
        val fits = space >= offer.amount
        val w = dialogWidth(b, 280)
        b.modal("withdraw", w, 96) {
            text(
                textAroundItemName("${offer.amount}× ", offer.item, " zurückziehen?"),
                x = 10, y = 10, width = w - 20, height = 20, align = TextAlign.CENTER, id = "withdraw/text",
            )
            text(
                if (fits) "Die Items kommen zurück in dein Inventar."
                else "Nur Platz für ${ShopFormat.number(space)} Stück, der Rest wird gedroppt.",
                x = 10, y = 36, width = w - 20, height = 18, align = TextAlign.CENTER,
                color = if (fits) GRAY else YELLOW, id = "withdraw/hint",
            )
            confirmButtons(
                w, 66, "withdraw_yes", "Zurückziehen", "withdraw_no", "Abbrechen",
                confirm = { onClick { ctx -> service.confirmWithdraw(ctx.player, s) } },
                back = { s.closeMyOfferDialogs() },
            )
        }
    }

    private fun priceEditModal(b: ScreenBuilder, player: Player, s: ShopSession, now: Long) {
        val id = s.priceEditOfferId ?: return
        val offer = market.offer(id)?.takeIf { it.sellerId == player.uniqueId && it.isActive(now) }
        if (offer == null) {
            s.priceEditOfferId = null
            return
        }
        val w = dialogWidth(b, 260)
        b.modal("price_edit", w, 112) {
            item(ScreenItem.of(offer.item, 1), x = 10, y = 10, id = "price_edit/item")
            text(ShopFormat.ellipsize(offer.name, w - 40), x = 30, y = 10, width = w - 40, id = "price_edit/name")
            text("Aktuell: ${ShopFormat.money(offer.pricePerUnit)}/Stk.", x = 30, y = 20, width = w - 40, color = GRAY, id = "price_edit/current")
            text("Neuer Preis", x = 10, y = 41, width = 70, color = GRAY, id = "price_edit/label")
            textField("price_edit_value", 84, 36, w - 94, 18) {
                value = s.priceEditText
                maxLength = 10
                numeric(1.0, Market.MAX_PRICE.toDouble())
                tooltip = Component.text("Taler pro Stück (1 bis ${ShopFormat.number(Market.MAX_PRICE)})")
                onInput { ctx ->
                    s.priceEditText = ctx.value
                    s.priceEditError = ShopValidation.price(ctx.value)
                    ctx.screen.refresh()
                }
            }
            s.priceEditError?.let { text(it, x = 10, y = 58, width = w - 20, color = RED, id = "price_edit/error") }
            confirmButtons(
                w, 82, "price_edit_save", "Speichern", "price_edit_cancel", "Abbrechen",
                confirm = {
                    enabled = s.priceEditError == null
                    onClick { ctx -> service.savePrice(ctx.player, s) }
                },
                back = { s.closeMyOfferDialogs() },
            )
        }
    }

    // ---- shared pieces --------------------------------------------------------------------------------------

    /** `1 Angebot`, `3 Angebote`. */
    private fun offerCount(count: Int): String = "$count ${if (count == 1) "Angebot" else "Angebote"}"

    /** Width of a small dialog: [preferred], but never wider than the screen allows. */
    private fun dialogWidth(b: ScreenBuilder, preferred: Int): Int = minOf(preferred, b.width - 24)

    /** A button that, like Escape on this modal, runs [back] and re-renders: the way out of a dialog. */
    private fun ModalBuilder.backButton(id: String, label: String, x: Int, y: Int, width: Int, back: () -> Unit) {
        button(id, label, x, y, width, 20) {
            onClick { ctx ->
                back()
                ctx.screen.refresh()
            }
        }
        onDismiss { ctx ->
            back()
            ctx.screen.refresh()
        }
    }

    /** The two centered buttons of a dialog [width] wide: [confirm] configures the first, the second goes [back]. */
    private fun ModalBuilder.confirmButtons(
        width: Int, y: Int, confirmId: String, confirmLabel: String, backId: String, backLabel: String,
        confirm: ButtonBuilder.() -> Unit, back: () -> Unit,
    ) {
        button(confirmId, confirmLabel, width / 2 - 94, y, 88, 20, confirm)
        backButton(backId, backLabel, width / 2 + 6, y, 88, back)
    }

    private companion object {
        const val PAD = 6
        const val HEADER_H = 26
        const val SIDEBAR_W = 84
        const val SCROLLBAR_W = 6
        const val TILE_PAD = 4
        const val TILE_GAP = 4
        const val TILE_MIN_W = 120
        const val TILE_H = 45
        const val MY_ROW_H = 30

        /** Size of the player inventory grid on the sell tab. */
        const val INVENTORY_W = 162
        const val INVENTORY_H = 76

        /** Narrower screens leave the title out of the header to make room for the tabs. */
        const val MIN_WIDTH_FOR_TITLE = 470

        val BG: Color = Color.fromARGB(0xF0, 0x16, 0x18, 0x1E)
        val HEADER_BG: Color = Color.fromRGB(0x23262E)
        val PANEL_BG: Color = Color.fromRGB(0x1D2027)
        val TAB_BG: Color = Color.fromRGB(0x2C303A)
        val TAB_HOVER: Color = Color.fromRGB(0x3A3F4B)
        val TAB_SELECTED: Color = Color.fromRGB(0x5A4A1E)
        val TILE_BG: Color = Color.fromRGB(0x2A2E37)
        val TILE_HOVER: Color = Color.fromRGB(0x3C4250)
        val GOLD: Color = Color.fromRGB(0xFFC94A)
        val GRAY: Color = Color.fromRGB(0xB0B4BE)
        val DARK_GRAY: Color = Color.fromRGB(0x767A84)
        val RED: Color = Color.fromRGB(0xFF5555)
        val RED_DIM: Color = Color.fromRGB(0x8A2E2E)
        val YELLOW: Color = Color.fromRGB(0xFFE066)
        val AQUA: Color = Color.fromRGB(0x66DDFF)
        val AQUA_DIM: Color = Color.fromRGB(0x2E6A7A)
    }
}
