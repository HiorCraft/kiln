package dev.kiln.example.server.shop

import dev.kiln.api.openFullscreen
import dev.kiln.api.showToast
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * `/shopdsl`: fullscreen player market (demo) rendered by the server with the screen DSL. The same [market] is used
 * by the Lua shop (`/shop`, [dev.kiln.example.server.shop.lua.LuaShopService]), which hooks into [onChange]. Owns the in-memory [market], the per-player [ShopSession]s and all
 * actions that move items or money. Everything runs on the main thread (command, screen handlers, scheduler).
 */
class ShopService(private val plugin: JavaPlugin) : TabExecutor, Listener {

    val market = Market<ItemStack>()
    private val sessions = HashMap<UUID, ShopSession>()
    private val view = ShopScreen(this)
    private var ticks = 0

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        seedDemoOffers()
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 40L, 40L)
    }

    // ---- opening / refreshing -------------------------------------------------------------------------------

    fun open(player: Player) {
        val session = sessions.getOrPut(player.uniqueId) { ShopSession(player.uniqueId) }
        session.handle = player.openFullscreen {
            view.render(this, player, session)
            onClose { ctx -> if (session.handle === ctx.screen) session.handle = null }
        }
    }

    /** Called after every change of offers or balances (from [refreshAll]); the Lua shop broadcasts its state. */
    var onChange: (() -> Unit)? = null

    /** Re-renders every open shop (offers or balances changed) and tells [onChange]. */
    fun refreshAll() {
        for (session in sessions.values) session.handle?.takeIf { it.isOpen }?.refresh()
        onChange?.invoke()
    }

    fun refresh(session: ShopSession) {
        session.handle?.takeIf { it.isOpen }?.refresh()
    }

    private fun tick() {
        ticks++
        for (session in sessions.values) {
            val handle = session.handle?.takeIf { it.isOpen } ?: continue
            when (session.tab) {
                // the sell tab shows the live inventory: re-render when it changed (pickups, drops, other plugins)
                ShopTab.SELL -> if (inventorySignature(handle.player) != session.inventorySignature) handle.refresh()
                // remaining times / expiry
                ShopTab.MY_OFFERS, ShopTab.MARKET -> if (ticks % 15 == 0) handle.refresh()
            }
        }
    }

    fun inventorySignature(player: Player): Int = player.inventory.storageContents.contentHashCode()

    // ---- inventory adapter ----------------------------------------------------------------------------------

    fun slots(player: Player, template: ItemStack): List<SlotStack?> = (0 until STORAGE_SLOTS).map { index ->
        player.inventory.getItem(index)?.takeUnless { it.isEmpty }?.let { SlotStack(it.isSimilar(template), it.amount) }
    }

    fun freeSpace(player: Player, template: ItemStack): Long = InventoryMath.freeSpace(slots(player, template), template.maxStackSize)

    fun countInInventory(player: Player, template: ItemStack): Int = InventoryMath.countSimilar(slots(player, template))

    fun apply(player: Player, template: ItemStack, changes: List<SlotChange>) {
        val inventory = player.inventory
        for (change in changes) {
            when {
                change.newAmount <= 0 -> inventory.setItem(change.slot, null)
                change.wasEmpty -> inventory.setItem(change.slot, template.asQuantity(change.newAmount))
                else -> inventory.getItem(change.slot)?.amount = change.newAmount
            }
        }
    }

    /** Gives [amount] items into the storage slots and drops what does not fit; returns the dropped amount. */
    fun give(player: Player, template: ItemStack, amount: Int): Int {
        val plan = InventoryMath.planInsert(slots(player, template), amount, template.maxStackSize)
        apply(player, template, plan.changes)
        var left = plan.leftover
        while (left > 0) {
            val chunk = minOf(left, template.maxStackSize)
            player.world.dropItemNaturally(player.location, template.asQuantity(chunk))
            left -= chunk
        }
        return plan.leftover
    }

    // ---- item names -----------------------------------------------------------------------------------------

    /** Plain, searchable name: custom name or the (server-side English) item name. */
    fun plainName(stack: ItemStack): String {
        val plain = runCatching { PlainTextComponentSerializer.plainText().serialize(stack.effectiveName()) }.getOrNull()
        if (!plain.isNullOrBlank() && !plain.startsWith("item.") && !plain.startsWith("block.")) return plain
        return stack.type.key.value().split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    fun suggestCategory(stack: ItemStack): Category =
        Category.suggest(stack.type.key.value(), stack.type.isBlock, stack.type.isEdible)

    // ---- buying ---------------------------------------------------------------------------------------------

    /** "Kaufen" in the buy dialog: check everything, then ask for confirmation. */
    fun requestBuy(player: Player, session: ShopSession) {
        val offer = session.buyOfferId?.let { market.offer(it) } ?: return session.closeBuyDialog().also { refresh(session) }
        val error = market.checkBuy(
            player.uniqueId, offer.id, session.buyAmount, System.currentTimeMillis(), freeSpace(player, offer.item), session.buyPriceSeen,
        )
        session.buyError = error?.message
        session.buyConfirm = error == null
        if (error is BuyError.PriceChanged) session.buyPriceSeen = error.newPrice
        refresh(session)
    }

    /** Confirmation accepted: execute the purchase and deliver the items. */
    fun confirmBuy(player: Player, session: ShopSession) {
        val offerId = session.buyOfferId ?: return
        val template = market.offer(offerId)?.item
        val space = template?.let { freeSpace(player, it) } ?: 0L
        when (val result = market.buy(player.uniqueId, offerId, session.buyAmount, System.currentTimeMillis(), space, session.buyPriceSeen)) {
            is BuyResult.Failure -> {
                session.buyConfirm = false
                session.buyError = result.error.message
                if (result.error is BuyError.PriceChanged) session.buyPriceSeen = result.error.newPrice
                refresh(session)
            }
            is BuyResult.Success -> {
                val offer = result.offer
                val dropped = give(player, offer.item, result.amount)
                val shulkerNote = if (session.buyShulker) " (Shulker-Lieferung ist nur Demo)" else ""
                session.closeBuyDialog()
                player.showToast(
                    Component.text("Gekauft!", NamedTextColor.GREEN),
                    Component.text("${result.amount}× ").append(offer.item.effectiveName())
                        .append(Component.text(" für ${ShopFormat.money(result.total)}")),
                    offer.item.asQuantity(result.amount.coerceAtMost(offer.item.maxStackSize)),
                )
                player.sendMessage(
                    Component.text("Du hast ${result.amount}× ${offer.name} für ${ShopFormat.money(result.total)} gekauft$shulkerNote.", NamedTextColor.GREEN),
                )
                if (dropped > 0) player.sendMessage(Component.text("$dropped Stück passten nicht ins Inventar und wurden gedroppt.", NamedTextColor.YELLOW))
                notifySeller(offer, result.amount, result.total, player.name)
                refreshAll()
            }
        }
    }

    private fun notifySeller(offer: Offer<ItemStack>, amount: Int, total: Long, buyerName: String) {
        val seller = offer.sellerId?.let { Bukkit.getPlayer(it) } ?: return
        if (!offer.notifyOnSale) return
        seller.showToast(
            Component.text("Verkauft!", NamedTextColor.GOLD),
            Component.text("$buyerName: ${amount}× ").append(offer.item.effectiveName()).append(Component.text(" (+${ShopFormat.money(total)})")),
            offer.item.asQuantity(amount.coerceAtMost(offer.item.maxStackSize)),
        )
        seller.sendMessage(Component.text("$buyerName hat ${amount}× ${offer.name} von dir gekauft: +${ShopFormat.money(total)}", NamedTextColor.GOLD))
    }

    // ---- selling --------------------------------------------------------------------------------------------

    /** Click on an inventory slot: choose that item; shift = whole stack as amount. */
    fun selectSellSlot(player: Player, session: ShopSession, slot: Int, shift: Boolean) {
        val stack = player.inventory.getItem(slot)?.takeUnless { it.isEmpty } ?: return
        val sameItem = session.sellTemplate?.isSimilar(stack) == true
        session.sellSlot = slot
        session.sellTemplate = stack.asOne()
        session.sellConfirm = false
        session.sellError = null
        if (!sameItem) {
            session.sellCategory = suggestCategory(stack)
            session.sellSubmitted = false
            session.sellAmount = "1"
        }
        if (shift) session.sellAmount = stack.amount.toString()
        refresh(session)
    }

    fun validateListing(player: Player, session: ShopSession): ListingValidation {
        val template = session.sellTemplate
        val available = template?.let { countInInventory(player, it) } ?: 0
        return ShopValidation.listing(template != null, available, session.sellAmount, session.sellPrice, session.sellCategory)
    }

    /** "Angebot einstellen": validate, then ask for confirmation. */
    fun requestListing(player: Player, session: ShopSession) {
        session.sellSubmitted = true
        session.sellError = null
        session.sellConfirm = validateListing(player, session).ok
        refresh(session)
    }

    fun confirmListing(player: Player, session: ShopSession) {
        session.sellConfirm = false
        val validation = validateListing(player, session)
        val template = session.sellTemplate
        val amount = validation.amount
        val price = validation.price
        val category = session.sellCategory
        if (!validation.ok || template == null || amount == null || price == null || category == null) {
            session.sellSubmitted = true
            refresh(session)
            return
        }
        val plan = InventoryMath.planRemove(slots(player, template), amount, session.sellSlot)
        if (plan == null) {
            session.sellError = "Nicht mehr genug Items im Inventar."
            refresh(session)
            return
        }
        apply(player, template, plan)
        val offer = market.createOffer(
            template.clone(), plainName(template), template.type.key.value(), category, price, amount,
            player.uniqueId, player.name, System.currentTimeMillis(), session.sellDuration, session.sellNotify,
        )
        session.clearSellForm()
        player.showToast(
            Component.text("Angebot eingestellt", NamedTextColor.GREEN),
            Component.text("${offer.amount}× ").append(template.effectiveName()).append(Component.text(" à ${ShopFormat.money(price)}")),
            template.asQuantity(amount.coerceAtMost(template.maxStackSize)),
        )
        refreshAll()
    }

    // ---- own offers -----------------------------------------------------------------------------------------

    fun confirmWithdraw(player: Player, session: ShopSession) {
        val offerId = session.withdrawOfferId ?: return
        session.closeMyOfferDialogs()
        val offer = market.withdraw(player.uniqueId, offerId)
        if (offer == null) {
            player.sendMessage(Component.text("Das Angebot existiert nicht mehr.", NamedTextColor.RED))
            refresh(session)
            return
        }
        val dropped = give(player, offer.item, offer.amount)
        player.showToast(
            Component.text("Angebot zurückgezogen", NamedTextColor.YELLOW),
            Component.text("${offer.amount}× ").append(offer.item.effectiveName())
                .append(Component.text(if (dropped > 0) " ($dropped gedroppt)" else " zurück im Inventar")),
            offer.item.asQuantity(offer.amount.coerceAtMost(offer.item.maxStackSize)),
        )
        if (dropped > 0) player.sendMessage(Component.text("Inventar voll: $dropped Stück wurden vor dir gedroppt.", NamedTextColor.YELLOW))
        refreshAll()
    }

    fun savePrice(player: Player, session: ShopSession) {
        val offerId = session.priceEditOfferId ?: return
        val error = ShopValidation.price(session.priceEditText)
        if (error != null) {
            session.priceEditError = error
            refresh(session)
            return
        }
        if (!market.changePrice(player.uniqueId, offerId, session.priceEditText.trim().toLong(), System.currentTimeMillis())) {
            session.priceEditError = "Das Angebot kann nicht mehr geändert werden."
            refresh(session)
            return
        }
        session.closeMyOfferDialogs()
        refreshAll()
    }

    // ---- demo data ------------------------------------------------------------------------------------------

    private fun seedDemoOffers() {
        val now = System.currentTimeMillis()
        val hans = UUID.nameUUIDFromBytes("kiln-demo:Hans".toByteArray())
        val greta = UUID.nameUUIDFromBytes("kiln-demo:Greta".toByteArray())
        var age = 0L
        fun offer(stack: ItemStack, price: Long, amount: Int, seller: UUID? = null, sellerName: String = "Server", category: Category? = null) {
            age += 7 * 60_000L
            market.createOffer(
                stack.asOne(), plainName(stack), stack.type.key.value(), category ?: suggestCategory(stack), price, amount,
                seller, sellerName, now - age, if (seller == null) null else ListingDuration.D7, false,
            )
        }

        val pickaxe = ItemStack.of(Material.DIAMOND_PICKAXE).apply {
            addUnsafeEnchantment(Enchantment.EFFICIENCY, 5)
            addUnsafeEnchantment(Enchantment.UNBREAKING, 3)
            editMeta { meta ->
                meta.displayName(Component.text("Spitzhacke des Zwergenkönigs", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(Component.text("Gräbt sich durch jeden Berg.", NamedTextColor.GRAY)))
            }
        }
        val apple = ItemStack.of(Material.GOLDEN_APPLE).apply {
            editMeta { meta -> meta.lore(listOf(Component.text("Frisch vom Server-Baum", NamedTextColor.YELLOW))) }
        }

        offer(ItemStack.of(Material.OAK_LOG), 2, 640)
        offer(ItemStack.of(Material.STONE_BRICKS), 1, 2_000)
        offer(ItemStack.of(Material.GLASS), 3, 256, hans, "Hans")
        offer(ItemStack.of(Material.IRON_PICKAXE), 45, 5)
        offer(pickaxe, 900, 1, greta, "Greta")
        offer(ItemStack.of(Material.SHEARS), 20, 3, hans, "Hans")
        offer(ItemStack.of(Material.DIAMOND), 120, 32)
        offer(ItemStack.of(Material.IRON_INGOT), 15, 128, greta, "Greta")
        offer(ItemStack.of(Material.COAL_ORE), 8, 48)
        offer(ItemStack.of(Material.EMERALD), 60, 12, hans, "Hans")
        offer(ItemStack.of(Material.BREAD), 4, 64)
        offer(ItemStack.of(Material.COOKED_BEEF), 6, 40, greta, "Greta")
        offer(apple, 250, 4)
        offer(ItemStack.of(Material.ENDER_PEARL), 35, 16, hans, "Hans")
        offer(ItemStack.of(Material.NAME_TAG), 80, 2)
        offer(ItemStack.of(Material.EXPERIENCE_BOTTLE), 12, 100, greta, "Greta", Category.OTHER)
    }

    // ---- command / events -----------------------------------------------------------------------------------

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Nur für Spieler.")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "open" -> open(player)
            "balance" -> player.sendMessage(Component.text("Kontostand: ${ShopFormat.money(market.balance(player.uniqueId))}", NamedTextColor.GOLD))
            else -> player.sendMessage(Component.text("Benutzung: /shopdsl [balance]", NamedTextColor.RED))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        if (args.size == 1) listOf("open", "balance").filter { it.startsWith(args[0].lowercase()) } else emptyList()

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // UI state only; balances and offers stay in the (in-memory) market
        sessions.remove(event.player.uniqueId)
    }

    private companion object {
        /** Hotbar (0..8) and main inventory (9..35); armor and offhand are never touched. */
        const val STORAGE_SLOTS = 36
    }
}
