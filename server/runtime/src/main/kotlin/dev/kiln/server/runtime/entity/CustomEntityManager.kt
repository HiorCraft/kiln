package dev.kiln.server.runtime.entity

import dev.kiln.protocol.message.AnimationMode
import dev.kiln.protocol.message.ClientboundMessage
import dev.kiln.protocol.message.EntityAppearance
import dev.kiln.protocol.message.EntityTraitsRemove
import dev.kiln.protocol.message.PlayAnimation
import dev.kiln.protocol.message.StopAnimation
import dev.kiln.server.api.entity.AnimationPlayback
import dev.kiln.server.api.entity.CustomEntities
import dev.kiln.server.api.entity.CustomEntity
import dev.kiln.server.api.entity.CustomEntityBuilder
import dev.kiln.server.api.entity.CustomEntityType
import dev.kiln.server.runtime.extension.ExtensionImpl
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import io.papermc.paper.event.player.PlayerUntrackEntityEvent
import net.kyori.adventure.key.Key
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityDimensions
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

/**
 * [CustomEntities]: types (namespace/model checked like items), living entities ([KilnMob] + [LiveEntity]),
 * behaviour goals, animations to the players that see an entity, and persistence in the chunk entry
 * `kiln:entities` (entities are never saved as entities; unknown types stay stored).
 */
class CustomEntityManager(
    private val logger: Logger,
    private val extensions: (String) -> ExtensionImpl?,
    private val send: (Player, ClientboundMessage) -> Unit,
) : CustomEntities, Listener {
    private val types = HashMap<Key, CustomEntityTypeImpl>()
    private val live = HashMap<UUID, LiveEntity>()

    // ---- types --------------------------------------------------------------------------------------------

    override fun register(plugin: Plugin, key: Key, init: CustomEntityBuilder.() -> Unit): CustomEntityType {
        val definition = CustomEntityBuilder().apply(init)
        val model = definition.model ?: key
        validate(plugin, key, model, definition)
        check(key !in types) { "Custom entity ${key.asString()} is already registered" }
        val type = CustomEntityTypeImpl(this, plugin, key, model, definition)
        types[key] = type
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) restore(chunk) { it.type == key.asString() }
        return type
    }

    /** The key's extension belongs to [plugin] and has the model's geometry; size and state names are valid. */
    private fun validate(plugin: Plugin, key: Key, model: Key, definition: CustomEntityBuilder) {
        val extension = extensions(key.namespace())?.asContentExtension()
            ?: throw IllegalArgumentException("No extension '${key.namespace()}' registered for ${key.asString()} (register the extension first)")
        require(extension.ownerName == plugin.name) { "${key.asString()}: extension '${key.namespace()}' belongs to ${extension.ownerName}" }
        val geo = "assets/${model.namespace()}/kiln/entity/${model.value()}.geo.json"
        require(model.namespace() == key.namespace() && geo in extension.files) { "Entity model ${model.asString()} not found (expected $geo)" }
        require(definition.width in SIZE_RANGE && definition.height in SIZE_RANGE) { "entity size must be 0.05..16" }
        definition.behavior.states.keys.forEach { EntityNames.state(it) }
    }

    override fun get(key: Key): CustomEntityType? = types[key]

    override fun of(entity: Entity?): CustomEntity? = entity?.let { live[it.uniqueId] }

    internal fun unregister(type: CustomEntityTypeImpl) {
        if (types[type.key] !== type) return
        live.values.filter { it.type === type }.forEach { store(it, remove = true) }
        types.remove(type.key)
    }

    fun removeAll(plugin: Plugin) = types.values.filter { it.plugin === plugin }.forEach { unregister(it) }

    fun removeNamespace(namespace: String) = types.values.filter { it.key.namespace() == namespace }.forEach { unregister(it) }

    /** Plugin disable: every entity back into its chunk. */
    fun storeAll() = live.values.toList().forEach { store(it, remove = true) }

    // ---- spawning -----------------------------------------------------------------------------------------

    internal fun spawn(type: CustomEntityTypeImpl, location: Location, stored: StoredEntity? = null): LiveEntity {
        val world = location.world ?: throw IllegalArgumentException("location without world")
        val level = (world as CraftWorld).handle
        val definition = type.definition
        val mob = createMob(level, definition, location, stored?.id)
        val bukkit = mob.bukkitEntity as Mob
        configure(bukkit, definition, stored?.health ?: definition.health)
        val entity = LiveEntity(this, type, mob, bukkit)
        stored?.let { entity.stateName = it.state }
        // registered before it enters the world: tracking (EntityAppearance) starts right away
        live[mob.uuid] = entity
        installGoals(entity)
        level.addFreshEntity(mob, CreatureSpawnEvent.SpawnReason.CUSTOM)
        if (stored == null) entity.hook("onSpawn") { definition.onSpawn?.invoke(entity.context) }
        return entity
    }

    private fun createMob(level: ServerLevel, definition: CustomEntityBuilder, location: Location, id: UUID?): KilnMob {
        val mob = KilnMob(level)
        mob.size = EntityDimensions.scalable(definition.width.toFloat(), definition.height.toFloat())
        mob.setPos(location.x, location.y, location.z)
        mob.setYRot(location.yaw)
        mob.setYHeadRot(location.yaw)
        mob.setYBodyRot(location.yaw)
        id?.let { mob.setUUID(it) }
        return mob
    }

    /** Never saved by the world, no zombie habits, the definition's attributes, and [health]. */
    private fun configure(bukkit: Mob, definition: CustomEntityBuilder, health: Double) {
        bukkit.isPersistent = false
        bukkit.removeWhenFarAway = false
        bukkit.isSilent = true
        bukkit.canPickupItems = false
        definition.displayName?.let { bukkit.customName(it) }
        bukkit.getAttribute(Attribute.MAX_HEALTH)?.baseValue = definition.health
        bukkit.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = definition.speed
        bukkit.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = definition.attackDamage
        bukkit.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = 0.0
        bukkit.health = health.coerceIn(MIN_HEALTH, definition.health)
    }

    private fun installGoals(entity: LiveEntity) {
        val behavior = entity.type.definition.behavior
        behavior.targeting?.let { t -> entity.mob.addKilnTargetGoal(1, TargetGoal(entity, t.range, t.filter)) }
        for (goal in behavior.goals) entity.mob.addKilnGoal(goal.priority, BehaviorGoal(entity, goal))
    }

    // ---- animations ---------------------------------------------------------------------------------------

    internal fun play(entity: LiveEntity, name: String, playback: AnimationPlayback, speed: Float) {
        // checked before storing: a stored bad message would fail for every later tracker
        val message = PlayAnimation(entity.mob.id, EntityNames.animation(name), playback.toMode(), EntityNames.speed(speed))
        if (playback == AnimationPlayback.ONCE) entity.persistentAnimations.remove(name) else entity.persistentAnimations[name] = message
        sendToTrackers(entity, message)
    }

    internal fun stop(entity: LiveEntity, name: String?) {
        name?.let { EntityNames.animation(it) }
        if (name == null) entity.persistentAnimations.clear() else entity.persistentAnimations.remove(name)
        sendToTrackers(entity, StopAnimation(entity.mob.id, name))
    }

    private fun AnimationPlayback.toMode(): Int = when (this) {
        AnimationPlayback.ONCE -> AnimationMode.ONCE
        AnimationPlayback.LOOP -> AnimationMode.LOOP
        AnimationPlayback.HOLD -> AnimationMode.HOLD
    }

    private fun sendToTrackers(entity: LiveEntity, message: ClientboundMessage) {
        entity.bukkit.trackedBy.forEach { send(it, message) }
    }

    private fun appearance(entity: LiveEntity): EntityAppearance {
        val definition = entity.type.definition
        return EntityAppearance(entity.mob.id, entity.type.model.asString(), definition.width.toFloat(), definition.height.toFloat(), definition.headBone)
    }

    // ---- persistence --------------------------------------------------------------------------------------

    private fun read(chunk: Chunk): List<StoredEntity> {
        val text = chunk.persistentDataContainer.get(CHUNK_KEY, PersistentDataType.STRING) ?: return emptyList()
        val decoded = CustomEntityStore.decode(text)
        if (decoded.invalid > 0) logger.warning("Chunk ${chunk.x}, ${chunk.z}: ${decoded.invalid} invalid custom entity entries ignored")
        return decoded.entries
    }

    private fun write(chunk: Chunk, list: List<StoredEntity>) {
        if (list.isEmpty()) chunk.persistentDataContainer.remove(CHUNK_KEY)
        else chunk.persistentDataContainer.set(CHUNK_KEY, PersistentDataType.STRING, CustomEntityStore.encode(list))
    }

    private fun snapshot(entity: LiveEntity): StoredEntity {
        val location = entity.bukkit.location
        return StoredEntity(
            entity.type.key.asString(), location.x, location.y, location.z, location.yaw, entity.bukkit.health, entity.stateName, entity.mob.uuid,
        )
    }

    /**
     * Writes [entity] into the chunk it stands in (and takes its older entry out of the chunk it was written to
     * before); [remove] also takes it out of the world.
     */
    private fun store(entity: LiveEntity, remove: Boolean) {
        val chunk = entity.bukkit.location.chunk
        val here = StoredIn(chunk.world.uid, chunk.chunkKey)
        if (entity.storedIn != here) forgetStored(entity)
        write(chunk, read(chunk).filterNot { it.id == entity.mob.uuid } + snapshot(entity))
        entity.storedIn = here
        if (remove) {
            live.remove(entity.mob.uuid)
            entity.bukkit.remove()
        }
    }

    /** Takes the saved entry of [entity] out of its chunk (death, removal, or a newer entry elsewhere). */
    private fun forgetStored(entity: LiveEntity) {
        val at = entity.storedIn ?: return
        entity.storedIn = null
        val world = Bukkit.getWorld(at.world) ?: return
        val chunk = world.getChunkAt(at.chunkKey)
        write(chunk, read(chunk).filterNot { it.id == entity.mob.uuid })
    }

    private fun restore(chunk: Chunk, filter: (StoredEntity) -> Boolean = { true }) {
        val stored = read(chunk)
        if (stored.isEmpty()) return
        val plan = CustomEntityStore.plan(stored, isLive = { it in live }, canSpawn = { types[Key.key(it.type)] != null && filter(it) })
        val kept = ArrayList(plan.keep)
        for (entry in plan.spawn) {
            val type = types.getValue(Key.key(entry.type))
            try {
                spawn(type, Location(chunk.world, entry.x, entry.y, entry.z, entry.yaw, 0f), entry)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Custom entity ${entry.type} could not be restored", e)
                kept += entry
            }
        }
        // living entities are not stored (stale copies of entities living elsewhere are dropped); unknown types stay
        write(chunk, kept)
    }

    // ---- events -------------------------------------------------------------------------------------------

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) = restore(event.chunk)

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        live.values.filter { it.bukkit.location.isIn(chunk) }.forEach { store(it, remove = true) }
    }

    /** Autosave and /save-all: the chunks get the current state of their entities (crash safety). */
    @EventHandler
    fun onWorldSave(event: WorldSaveEvent) {
        live.values.filter { it.bukkit.world == event.world }.forEach { store(it, remove = false) }
    }

    @EventHandler
    fun onTrack(event: PlayerTrackEntityEvent) {
        val entity = live[event.entity.uniqueId] ?: return
        send(event.player, appearance(entity))
        entity.persistentAnimations.values.forEach { send(event.player, it) }
    }

    @EventHandler
    fun onUntrack(event: PlayerUntrackEntityEvent) {
        if (event.entity.uniqueId in live) send(event.player, EntityTraitsRemove(listOf(event.entity.entityId)))
    }

    /** Every [TICK_INTERVAL] ticks: ambient and step sounds. */
    fun tick() {
        for (entity in live.values) {
            if (!entity.bukkit.isValid) continue
            playAmbientSound(entity)
            playStepSound(entity)
        }
    }

    private fun playAmbientSound(entity: LiveEntity) {
        val ambient = entity.type.definition.sounds.ambient ?: return
        if (Random.nextDouble() < AMBIENT_CHANCE) entity.sound(ambient)
    }

    private fun playStepSound(entity: LiveEntity) {
        val sounds = entity.type.definition.sounds
        val step = sounds.step ?: return
        entity.stepTicks += TICK_INTERVAL
        if (isWalking(entity.bukkit) && entity.stepTicks >= sounds.stepInterval) {
            entity.stepTicks = 0
            entity.sound(step, STEP_VOLUME)
        }
    }

    private fun isWalking(mob: Mob): Boolean {
        val moving = mob.pathfinder.hasPath() || mob.velocity.clone().setY(0).lengthSquared() > MOVING_SPEED_SQUARED
        return moving && mob.isOnGround
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = live[event.entity.uniqueId] ?: return
        entity.type.definition.sounds.hurt?.let { if (entity.bukkit.health - event.finalDamage > 0) entity.sound(it) }
        val handler = entity.type.definition.onDamage ?: return
        val source = (event as? EntityDamageByEntityEvent)?.damager
        entity.hook("onDamage") { handler(entity.context, source, event.finalDamage) }
    }

    /** Removal without death (peaceful despawn, other plugins, entity unload): never resurrect a discarded entity. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        val entity = live[event.entity.uniqueId] ?: return
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD) {
            if (entity.bukkit.location.isChunkLoaded) store(entity, remove = false)
        } else {
            forgetStored(entity)
        }
        live.remove(entity.mob.uuid)
    }

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val entity = live.remove(event.entity.uniqueId) ?: return
        forgetStored(entity)
        event.drops.clear() // no zombie loot; onDeath decides
        entity.type.definition.sounds.death?.let { entity.sound(it) }
        entity.type.definition.onDeath?.let { handler -> entity.hook("onDeath") { handler(entity.context) } }
    }

    internal fun log(entity: LiveEntity, what: String, e: Throwable) =
        logger.log(Level.SEVERE, "Custom entity ${entity.type.key.asString()} ($what) of ${entity.type.plugin.name} failed", e)

    private fun Location.isIn(chunk: Chunk): Boolean = world == chunk.world && blockX shr 4 == chunk.x && blockZ shr 4 == chunk.z

    companion object {
        val CHUNK_KEY: NamespacedKey = NamespacedKey("kiln", "entities")

        /** Ticks between two [tick] calls. */
        const val TICK_INTERVAL = 10

        /** Per [TICK_INTERVAL]: on average one ambient sound every 8 seconds. */
        private const val AMBIENT_CHANCE = 1.0 / 16

        private val SIZE_RANGE = 0.05..16.0
        private const val MIN_HEALTH = 0.1
        private const val STEP_VOLUME = 0.6f

        /** Horizontal speed (squared, blocks per tick) above which an entity counts as moving. */
        private const val MOVING_SPEED_SQUARED = 0.0004
    }
}
