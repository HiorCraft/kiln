package dev.kiln.server.runtime.furniture

import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.EntityTraits
import dev.kiln.protocol.message.EntityTraitsRemove
import dev.kiln.server.api.furniture.Furniture
import dev.kiln.server.api.furniture.FurnitureBuilder
import dev.kiln.server.api.furniture.FurnitureHitbox
import dev.kiln.server.api.furniture.FurnitureRegistry
import dev.kiln.server.api.furniture.FurnitureType
import dev.kiln.server.runtime.extension.ExtensionImpl
import dev.kiln.server.runtime.item.CustomItemCatalog
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import io.papermc.paper.event.player.PlayerUntrackEntityEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.floor

/**
 * [FurnitureRegistry]: furniture is stored per chunk (`kiln:furniture`, [FurnitureStore]) and shown by
 * non-persistent entities spawned while the chunk is loaded and its type is registered: one item display for the
 * model and a [KilnInteraction] per hit box.
 */
class FurnitureManager(
    private val plugin: Plugin,
    private val logger: Logger,
    extensions: (String) -> ExtensionImpl?,
    /** Sends a Kiln message to a player with a session (entity traits). */
    private val sendTraits: (Player, ClientboundMessage) -> Unit,
) : FurnitureRegistry, Listener {
    private val types = CustomItemCatalog<FurnitureTypeImpl> { id -> extensions(id)?.asContentExtension() }
    private val live = HashMap<UUID, LiveFurniture>()
    private val byEntity = HashMap<UUID, LiveFurniture>()
    private val seats = HashMap<UUID, LiveFurniture>()

    /** Live furniture per origin chunk (world + chunk key), so unloading a chunk does not scan everything. */
    private val byChunk = HashMap<Pair<UUID, Long>, MutableSet<LiveFurniture>>()

    /** Tick of each player's last handled furniture interaction (one action per click). */
    private val lastInteract = HashMap<UUID, Long>()

    // ---- registration -------------------------------------------------------------------------------------

    override fun register(plugin: Plugin, key: Key, init: FurnitureBuilder.() -> Unit): FurnitureType {
        val definition = FurnitureBuilder().apply(init)
        val type = FurnitureTypeImpl(this, plugin, key, definition)
        types.add(plugin.name, key, definition.model, type)
        // already loaded chunks with furniture of this type
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) spawnStored(chunk) { it.type == key.asString() }
        return type
    }

    override fun get(key: Key): FurnitureType? = types.get(key)

    override fun at(entity: Entity): Furniture? = byEntity[entity.uniqueId]

    fun removeAll(plugin: Plugin) {
        live.values.filter { it.type.plugin === plugin }.forEach { despawn(it) }
        types.removeOwner(plugin.name)
    }

    /** The extension [namespace] was unregistered: its furniture types go (placed furniture stays stored). */
    fun removeNamespace(namespace: String) {
        val removed = types.removeNamespace(namespace).toSet()
        live.values.filter { it.type in removed }.forEach { despawn(it) }
    }

    internal fun unregister(type: FurnitureTypeImpl) {
        live.values.filter { it.type === type }.forEach { despawn(it) }
        types.remove(type.key, type)
    }

    /** Removes every furniture entity (plugin disable); the chunk data stays. */
    fun despawnAll() {
        live.values.toList().forEach { despawn(it) }
        seats.keys.mapNotNull(Bukkit::getEntity).forEach { it.remove() }
        seats.clear()
    }

    // ---- placing / removing -------------------------------------------------------------------------------

    internal fun place(type: FurnitureTypeImpl, location: Location, yaw: Float): Furniture {
        val world = location.world ?: throw IllegalArgumentException("location without world")
        val stored = StoredFurniture(type.key.asString(), location.x, location.y, location.z,
            FurnitureGeometry.snapYaw(yaw, type.definition.hitboxes.isNotEmpty()), UUID.randomUUID())
        val chunk = location.chunk
        writeChunk(chunk, readChunk(chunk) + stored)
        return spawn(world, type, stored)
    }

    internal fun remove(furniture: LiveFurniture, dropItem: Boolean) {
        val chunk = furniture.location.chunk
        val stored = readChunk(chunk)
        // a stale handle (chunk reloaded meanwhile) or a second remove: act on what is there now, drop at most once
        if (stored.none { it.id == furniture.id }) return
        writeChunk(chunk, stored.filterNot { it.id == furniture.id })
        live[furniture.id]?.let { despawn(it) }
        if (dropItem) furniture.type.definition.item?.let { furniture.location.world.dropItemNaturally(furniture.location, it.create()) }
    }

    private fun spawnStored(chunk: Chunk, filter: (StoredFurniture) -> Boolean = { true }) {
        for (stored in readChunk(chunk)) {
            if (!filter(stored) || stored.id in live) continue
            val type = types.get(Key.key(stored.type)) ?: continue // plugin not (yet) there: stays stored
            try {
                spawn(chunk.world, type, stored)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Furniture ${stored.type} at ${stored.x}, ${stored.y}, ${stored.z} could not be spawned", e)
            }
        }
    }

    private fun spawn(world: World, type: FurnitureTypeImpl, stored: StoredFurniture): LiveFurniture {
        val display = spawnModel(world, type, stored)
        val level = (world as CraftWorld).handle
        lateinit var furniture: LiveFurniture
        val parts = type.definition.hitboxes.map { box ->
            val feet = FurnitureGeometry.position(stored.origin, stored.yaw, box.toGeometry())
            val entity = KilnInteraction(level, box.solid) { player -> onAttacked(furniture, player) }
            entity.setPos(feet.x, feet.y, feet.z)
            entity.setWidth(box.width.toFloat())
            entity.setHeight(box.height.toFloat())
            entity.bukkitEntity.isPersistent = false
            entity
        }
        furniture = LiveFurniture(this, type, stored, display, parts.map { it.bukkitEntity }, parts.filter { it.solid }.map { it.id }.toSet())
        // registered before the parts enter the world: adding an entity starts tracking (PlayerTrackEntityEvent)
        // right away, and the solid trait must be known by then
        track(furniture)
        for (part in parts) level.addFreshEntity(part, CreatureSpawnEvent.SpawnReason.CUSTOM)
        return furniture
    }

    private fun spawnModel(world: World, type: FurnitureTypeImpl, stored: StoredFurniture): ItemDisplay {
        // item displays draw the item model turned by 180° around Y: + 180 keeps the model's north at the back
        val at = Location(world, stored.x, stored.y + type.definition.modelHeight, stored.z, stored.yaw + 180f, 0f)
        return world.spawn(at, ItemDisplay::class.java) {
            it.isPersistent = false
            it.setItemStack(ItemType.PAPER.createItemStack().apply { setData(DataComponentTypes.ITEM_MODEL, type.model) })
        }
    }

    private fun track(furniture: LiveFurniture) {
        live[furniture.id] = furniture
        byChunk.getOrPut(chunkKey(furniture)) { HashSet() } += furniture
        byEntity[furniture.display.uniqueId] = furniture
        for (part in furniture.parts) byEntity[part.uniqueId] = furniture
    }

    private fun despawn(furniture: LiveFurniture) {
        removeSeats(furniture)
        // players still tracking a solid box must drop its trait (a later entity may reuse the id)
        if (furniture.solidIds.isNotEmpty()) {
            val trackers = furniture.parts.flatMap { it.trackedBy }.toSet()
            for (player in trackers) sendTraits(player, EntityTraitsRemove(furniture.solidIds.toList()))
        }
        untrack(furniture)
        furniture.display.remove()
        for (part in furniture.parts) {
            part.passengers.forEach { it.leaveVehicle() }
            part.remove()
        }
    }

    /** Riders sit on separate seat entities: ejects them and removes their seats. */
    private fun removeSeats(furniture: LiveFurniture) {
        for ((seatId, owner) in seats.entries.toList()) {
            if (owner !== furniture) continue
            seats -= seatId
            Bukkit.getEntity(seatId)?.let { seat ->
                seat.passengers.forEach { it.leaveVehicle() }
                seat.remove()
            }
        }
    }

    private fun untrack(furniture: LiveFurniture) {
        live.remove(furniture.id)
        val chunkKey = chunkKey(furniture)
        byChunk[chunkKey]?.let { set ->
            set.remove(furniture)
            if (set.isEmpty()) byChunk.remove(chunkKey)
        }
        byEntity.remove(furniture.display.uniqueId)
        for (part in furniture.parts) byEntity.remove(part.uniqueId)
    }

    private fun chunkKey(furniture: LiveFurniture): Pair<UUID, Long> {
        val stored = furniture.stored
        return furniture.display.world.uid to Chunk.getChunkKey(floor(stored.x).toInt() shr 4, floor(stored.z).toInt() shr 4)
    }

    private fun readChunk(chunk: Chunk): List<StoredFurniture> {
        val text = chunk.persistentDataContainer.get(CHUNK_KEY, PersistentDataType.STRING) ?: return emptyList()
        val decoded = FurnitureStore.decode(text)
        if (decoded.invalid > 0) logger.warning("Chunk ${chunk.x}, ${chunk.z} in ${chunk.world.name}: ${decoded.invalid} invalid furniture entries ignored")
        return decoded.entries
    }

    private fun writeChunk(chunk: Chunk, list: List<StoredFurniture>) {
        if (list.isEmpty()) chunk.persistentDataContainer.remove(CHUNK_KEY)
        else chunk.persistentDataContainer.set(CHUNK_KEY, PersistentDataType.STRING, FurnitureStore.encode(list))
    }

    // ---- seats --------------------------------------------------------------------------------------------

    /** [offset].y is the height of the sitting surface above the origin; the rider's hips end up there. */
    internal fun seat(furniture: LiveFurniture, player: Player, offset: Vector): Boolean {
        // already on one of our seats: switch (the old seat goes without standing up); other vehicles stay
        val current = player.vehicle
        if (current != null) {
            if (current.uniqueId !in seats) return false
            seats -= current.uniqueId
            player.leaveVehicle()
            current.remove()
        }
        val pos = FurnitureGeometry.rotate(offset.toVec(), furniture.stored.yaw)
        val at = furniture.location.clone().add(pos.x, pos.y, pos.z)
        val seat = at.world.spawn(at, ItemDisplay::class.java) { it.isPersistent = false }
        seats[seat.uniqueId] = furniture
        if (!seat.addPassenger(player)) {
            seats -= seat.uniqueId
            seat.remove()
            return false
        }
        return true
    }

    internal fun sit(furniture: LiveFurniture, player: Player, near: Location?): Boolean {
        val offsets = furniture.type.definition.seats
        if (offsets.isEmpty()) return false
        val positions = offsets.map { furniture.stored.origin + FurnitureGeometry.rotate(it.toVec(), furniture.stored.yaw) }
        // taken = a seat entity of this furniture stands there (also seats placed through Furniture.seat); the
        // clicking player's own seat does not count, so a click elsewhere moves them
        val riders = seats.filter { it.value === furniture && it.key != player.vehicle?.uniqueId }.keys
            .mapNotNull(Bukkit::getEntity).map { it.location.toVec() }
        val occupied = FurnitureGeometry.occupiedSeats(positions, riders)
        val index = FurnitureGeometry.nearestFreeSeat(positions, occupied, (near ?: player.location).toVec()) ?: return false
        if (sitsAt(player, furniture, positions[index])) return true // clicking the seat you sit on changes nothing
        return seat(furniture, player, offsets[index])
    }

    private fun sitsAt(player: Player, furniture: LiveFurniture, position: Vec): Boolean {
        val own = player.vehicle?.takeIf { seats[it.uniqueId] === furniture }?.location ?: return false
        return FurnitureGeometry.occupiedSeats(listOf(position), listOf(own.toVec())).isNotEmpty()
    }

    /**
     * Where a rider stands up: on top of the furniture's hit boxes under the seat (vanilla would put them at the
     * seat's height, inside a solid box, from where they fall into the floor).
     */
    private fun standUpLocation(furniture: LiveFurniture, seat: Location, player: Player): Location {
        val hitboxes = furniture.type.definition.hitboxes
        val boxes = FurnitureGeometry.boxes(furniture.stored.origin, furniture.stored.yaw, hitboxes.map { it.toGeometry() })
            .filterIndexed { i, _ -> hitboxes[i].solid }
        val top = boxes.filter { seat.x in it.minX..it.maxX && seat.z in it.minZ..it.maxZ }.maxOfOrNull { it.maxY }
        return Location(seat.world, seat.x, maxOf(top ?: seat.y, furniture.stored.y), seat.z, player.location.yaw, player.location.pitch)
    }

    // ---- events -------------------------------------------------------------------------------------------

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        // hit boxes across a chunk border are dropped when that neighbour unloads: rebuild such furniture
        val broken = live.values.filter { furniture -> furniture.parts.any { !it.isValid } || !furniture.display.isValid }
        for (furniture in broken) {
            despawn(furniture)
            if (furniture.location.isChunkLoaded) spawn(furniture.location.world, furniture.type, furniture.stored)
        }
        spawnStored(event.chunk)
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        byChunk[chunk.world.uid to Chunk.getChunkKey(chunk.x, chunk.z)]?.toList()?.forEach { despawn(it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastInteract -= event.player.uniqueId
    }

    /** Right click on a block with a furniture item places it on that face. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onPlace(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useItemInHand() == Event.Result.DENY) return
        val stack = event.item ?: return
        val type = types.all().firstOrNull { it.definition.item?.matches(stack) == true } ?: return
        val clicked = event.clickedBlock ?: return
        // doors, chests, levers ... keep working while holding furniture (sneak to place on them)
        if (clicked.type.isInteractable && !event.player.isSneaking) return
        event.isCancelled = true
        val target = clicked.getRelative(event.blockFace)
        if (!target.isReplaceable) return
        val player = event.player
        if (player.gameMode == GameMode.ADVENTURE || player.gameMode == GameMode.SPECTATOR) return
        if (!mayBuild(event, player, target, clicked, stack)) return
        val origin = Vec(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
        val yaw = FurnitureGeometry.snapYaw(player.location.yaw + 180f, type.definition.hitboxes.isNotEmpty())
        val boxes = FurnitureGeometry.boxes(origin, yaw, type.definition.hitboxes.map { it.toGeometry() })
        if (isObstructed(target.world, boxes)) return
        place(type, Location(target.world, origin.x, origin.y, origin.z), yaw)
        if (player.gameMode != GameMode.CREATIVE) stack.amount -= 1
    }

    /** Protection plugins (regions, spawn protection) decide through the usual block place event. */
    private fun mayBuild(event: PlayerInteractEvent, player: Player, target: Block, clicked: Block, stack: ItemStack): Boolean {
        val placeCheck = BlockPlaceEvent(target, target.state, clicked, stack, player, true, event.hand ?: EquipmentSlot.HAND)
        return placeCheck.callEvent() && placeCheck.canBuild()
    }

    /** Any block that is not replaceable (also flowers, torches) and any entity (other furniture, players) is in the way. */
    private fun isObstructed(world: World, boxes: List<Box>): Boolean =
        FurnitureGeometry.blocked(boxes) { x, y, z -> !world.getBlockAt(x, y, z).isReplaceable } ||
            boxes.any { b -> world.getNearbyEntities(BoundingBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) { it !is Item }.isNotEmpty() }

    /** Left click on a hit box (reported by [KilnInteraction]): `onBreak`, by default remove (+ drop in survival). */
    private fun onAttacked(furniture: LiveFurniture, player: Player) {
        if (furniture.id !in live) return
        hook(furniture, "onBreak") {
            val handler = furniture.type.definition.onBreak
            if (handler != null) handler(player, furniture) else furniture.remove(dropItem = player.gameMode != GameMode.CREATIVE)
        }
    }

    /** Left click on the model (item display). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAttack(event: PrePlayerAttackEntityEvent) {
        val furniture = byEntity[event.attacked.uniqueId] ?: return
        event.isCancelled = true
        onAttacked(furniture, event.player)
    }

    /**
     * Right click on a part. Depending on the entity the client sends "interact at" (with the clicked point), a
     * plain "interact", or both; all arrive as [PlayerInteractEntityEvent]. The first one per tick acts.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val furniture = byEntity[event.rightClicked.uniqueId] ?: return
        event.isCancelled = true
        val player = event.player
        if (!isFirstInteractThisTick(player)) return
        val click = (event as? PlayerInteractAtEntityEvent)?.let { event.rightClicked.location.clone().add(it.clickedPosition) }
        val handler = furniture.type.definition.onInteract
        hook(furniture, "onInteract") {
            if (handler != null) handler(player, furniture) else furniture.sit(player, click ?: event.rightClicked.location)
        }
    }

    private fun isFirstInteractThisTick(player: Player): Boolean {
        val tick = player.world.gameTime
        return lastInteract.put(player.uniqueId, tick) != tick
    }

    @EventHandler
    fun onTrack(event: PlayerTrackEntityEvent) {
        val furniture = byEntity[event.entity.uniqueId] ?: return
        if (event.entity.entityId in furniture.solidIds) sendTraits(event.player, EntityTraits(event.entity.entityId, solid = true))
    }

    @EventHandler
    fun onUntrack(event: PlayerUntrackEntityEvent) {
        val furniture = byEntity[event.entity.uniqueId] ?: return
        if (event.entity.entityId in furniture.solidIds) sendTraits(event.player, EntityTraitsRemove(listOf(event.entity.entityId)))
    }

    @EventHandler
    fun onDismount(event: EntityDismountEvent) {
        val seat = event.dismounted
        val furniture = seats.remove(seat.uniqueId) ?: return
        val player = event.entity as? Player
        val seatLocation = seat.location
        if (!plugin.isEnabled) {
            seat.remove() // Kiln shuts down: no task can be scheduled any more
            return
        }
        // after vanilla's dismount handling: remove the seat, stand the player up on the furniture
        Bukkit.getScheduler().runTask(plugin, Runnable {
            seat.remove()
            if (player == null || !player.isOnline || player.vehicle != null) return@Runnable
            val sameWorld = player.world == seatLocation.world
            val distance = if (sameWorld) player.location.distanceSquared(seatLocation) else Double.MAX_VALUE
            if (FurnitureGeometry.shouldStandUp(!player.isDead, sameWorld, distance)) player.teleport(standUpLocation(furniture, seatLocation, player))
        })
    }

    private inline fun hook(furniture: LiveFurniture, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "Furniture ${furniture.type.key.asString()} ($what) of ${furniture.type.plugin.name} failed", e)
        }
    }

    companion object {
        val CHUNK_KEY: NamespacedKey = NamespacedKey("kiln", "furniture")
    }
}

private val StoredFurniture.origin: Vec get() = Vec(x, y, z)

private fun Vector.toVec(): Vec = Vec(x, y, z)

private fun Location.toVec(): Vec = Vec(x, y, z)

private fun FurnitureHitbox.toGeometry(): Hitbox = Hitbox(width, height, offset.toVec(), solid)
