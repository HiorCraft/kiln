package dev.kiln.server.runtime.furniture

import dev.kiln.protocol.EntityTraits
import dev.kiln.protocol.EntityTraitsRemove
import dev.kiln.server.api.furniture.Furniture
import dev.kiln.server.api.furniture.FurnitureBuilder
import dev.kiln.server.api.furniture.FurnitureRegistry
import dev.kiln.server.api.furniture.FurnitureType
import dev.kiln.server.runtime.extension.ExtensionImpl
import dev.kiln.server.runtime.item.ContentExtension
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
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

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
    private val sendTraits: (Player, Any) -> Unit,
) : FurnitureRegistry, Listener {
    private val types = CustomItemCatalog<FurnitureTypeImpl> { id -> extensions(id)?.let { ContentExtension(it.plugin.name, it.pkg.files.toSet()) } }
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

    internal fun unregister(type: FurnitureTypeImpl) {
        live.values.filter { it.type === type }.forEach { despawn(it) }
        types.remove(type.key)
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
            val key = Key.key(stored.type)
            val type = types.get(key) ?: continue // plugin not (yet) there: stays stored
            try {
                spawn(chunk.world, type, stored)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Furniture ${stored.type} at ${stored.x}, ${stored.y}, ${stored.z} could not be spawned", e)
            }
        }
    }

    private fun spawn(world: World, type: FurnitureTypeImpl, stored: StoredFurniture): LiveFurniture {
        val origin = Vec(stored.x, stored.y, stored.z)
        val definition = type.definition
        // item displays draw the item model turned by 180° around Y: + 180 keeps the model's north at the back
        val display = world.spawn(Location(world, stored.x, stored.y + definition.modelHeight, stored.z, stored.yaw + 180f, 0f), ItemDisplay::class.java) {
            it.isPersistent = false
            it.setItemStack(ItemType.PAPER.createItemStack().apply { setData(DataComponentTypes.ITEM_MODEL, type.model) })
        }
        val level = (world as CraftWorld).handle
        lateinit var furniture: LiveFurniture
        val parts = definition.hitboxes.map { box ->
            val feet = FurnitureGeometry.position(origin, stored.yaw, Hitbox(box.width, box.height, Vec(box.offset.x, box.offset.y, box.offset.z), box.solid))
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
        live[stored.id] = furniture
        byChunk.getOrPut(chunkKey(stored, world)) { HashSet() } += furniture
        byEntity[display.uniqueId] = furniture
        for (part in furniture.parts) byEntity[part.uniqueId] = furniture
        for (part in parts) level.addFreshEntity(part, CreatureSpawnEvent.SpawnReason.CUSTOM)
        return furniture
    }

    private fun despawn(furniture: LiveFurniture) {
        // riders sit on separate seat entities: eject them and remove their seats
        for ((seatId, owner) in seats.entries.toList()) {
            if (owner !== furniture) continue
            seats -= seatId
            Bukkit.getEntity(seatId)?.let { seat ->
                seat.passengers.forEach { it.leaveVehicle() }
                seat.remove()
            }
        }
        // players still tracking a solid box must drop its trait (a later entity may reuse the id)
        if (furniture.solidIds.isNotEmpty()) {
            val trackers = furniture.parts.flatMap { it.trackedBy }.toSet()
            for (player in trackers) sendTraits(player, EntityTraitsRemove(furniture.solidIds.toList()))
        }
        live.remove(furniture.id)
        byChunk[chunkKey(furniture.stored, furniture.display.world)]?.let { set ->
            set.remove(furniture)
            if (set.isEmpty()) byChunk.remove(chunkKey(furniture.stored, furniture.display.world))
        }
        byEntity.remove(furniture.display.uniqueId)
        furniture.display.remove()
        for (part in furniture.parts) {
            byEntity.remove(part.uniqueId)
            part.passengers.forEach { it.leaveVehicle() }
            part.remove()
        }
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

    /** [offset].y is the height of the sitting surface above the origin; the rider's hips end up there. */
    internal fun seat(furniture: LiveFurniture, player: Player, offset: Vector): Boolean {
        if (player.isInsideVehicle) return false
        val pos = FurnitureGeometry.rotate(Vec(offset.x, offset.y, offset.z), furniture.stored.yaw)
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
        val origin = Vec(furniture.stored.x, furniture.stored.y, furniture.stored.z)
        val positions = offsets.map { origin + FurnitureGeometry.rotate(Vec(it.x, it.y, it.z), furniture.stored.yaw) }
        // taken = a seat entity of this furniture stands there (also seats placed through Furniture.seat)
        val riders = seats.filter { it.value === furniture }.keys.mapNotNull(Bukkit::getEntity).map { Vec(it.location.x, it.location.y, it.location.z) }
        val occupied = FurnitureGeometry.occupiedSeats(positions, riders)
        val point = (near ?: player.location).let { Vec(it.x, it.y, it.z) }
        val index = FurnitureGeometry.nearestFreeSeat(positions, occupied, point) ?: return false
        return seat(furniture, player, offsets[index])
    }

    /**
     * Where a rider stands up: on top of the furniture's hit boxes under the seat (vanilla would put them at the
     * seat's height, inside a solid box, from where they fall into the floor).
     */
    private fun standUpLocation(furniture: LiveFurniture, seat: Location, player: Player): Location {
        val boxes = FurnitureGeometry.boxes(
            Vec(furniture.stored.x, furniture.stored.y, furniture.stored.z), furniture.stored.yaw,
            furniture.type.definition.hitboxes.map { Hitbox(it.width, it.height, Vec(it.offset.x, it.offset.y, it.offset.z), it.solid) },
        ).filterIndexed { i, _ -> furniture.type.definition.hitboxes[i].solid }
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

    private fun chunkKey(stored: StoredFurniture, world: World): Pair<UUID, Long> =
        world.uid to Chunk.getChunkKey(kotlin.math.floor(stored.x).toInt() shr 4, kotlin.math.floor(stored.z).toInt() shr 4)

    /** Right click on a block with a furniture item places it on that face. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onPlace(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return
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
        // protection plugins (regions, spawn protection) decide through the usual block place event
        val placeCheck = BlockPlaceEvent(target, target.state, clicked, stack, player, true, event.hand ?: EquipmentSlot.HAND)
        if (!placeCheck.callEvent() || !placeCheck.canBuild()) return
        val origin = Vec(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
        val yaw = FurnitureGeometry.snapYaw(player.location.yaw + 180f, type.definition.hitboxes.isNotEmpty())
        val boxes = FurnitureGeometry.boxes(origin, yaw, type.definition.hitboxes.map { Hitbox(it.width, it.height, Vec(it.offset.x, it.offset.y, it.offset.z), it.solid) })
        // any block that is not replaceable (also flowers, torches) and any entity (other furniture, players) is in the way
        if (FurnitureGeometry.blocked(boxes) { x, y, z -> target.world.getBlockAt(x, y, z).let { !it.isReplaceable } }) return
        if (boxes.any { b -> target.world.getNearbyEntities(org.bukkit.util.BoundingBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) { it !is org.bukkit.entity.Item }.isNotEmpty() }) return
        place(type, Location(target.world, origin.x, origin.y, origin.z), yaw)
        if (player.gameMode != GameMode.CREATIVE) stack.amount -= 1
    }

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
        val tick = player.world.gameTime
        if (lastInteract[player.uniqueId] == tick) return
        lastInteract[player.uniqueId] = tick
        val click = (event as? PlayerInteractAtEntityEvent)?.let { event.rightClicked.location.clone().add(it.clickedPosition) }
        val handler = furniture.type.definition.onInteract
        hook(furniture, "onInteract") {
            if (handler != null) handler(player, furniture) else furniture.sit(player, click ?: event.rightClicked.location)
        }
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

class FurnitureTypeImpl(
    private val manager: FurnitureManager,
    override val plugin: Plugin,
    override val key: Key,
    val definition: FurnitureBuilder,
) : FurnitureType {
    val model: Key = CustomItemCatalog.modelOf(key, definition.model)

    override fun place(location: Location, yaw: Float): Furniture = manager.place(this, location, yaw)

    override fun unregister() = manager.unregister(this)

    override fun toString(): String = "FurnitureType(${key.asString()})"
}

class LiveFurniture(
    private val manager: FurnitureManager,
    override val type: FurnitureTypeImpl,
    val stored: StoredFurniture,
    val display: ItemDisplay,
    val parts: List<Entity>,
    /** Network ids of the solid hit boxes. */
    val solidIds: Set<Int>,
) : Furniture {
    override val id: UUID get() = stored.id

    override val location: Location get() = Location(display.world, stored.x, stored.y, stored.z, stored.yaw, 0f)

    override fun remove(dropItem: Boolean) = manager.remove(this, dropItem)

    override fun seat(player: Player, offset: Vector) {
        manager.seat(this, player, offset)
    }

    override fun sit(player: Player, near: Location?): Boolean = manager.sit(this, player, near)
}
