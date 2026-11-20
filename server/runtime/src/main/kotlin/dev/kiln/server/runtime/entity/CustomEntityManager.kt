package dev.kiln.server.runtime.entity

import dev.kiln.protocol.AnimationMode
import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.EntityAppearance
import dev.kiln.protocol.EntityTraitsRemove
import dev.kiln.protocol.PlayAnimation
import dev.kiln.protocol.StopAnimation
import dev.kiln.server.api.entity.AnimationPlayback
import dev.kiln.server.api.entity.CustomEntities
import dev.kiln.server.api.entity.CustomEntity
import dev.kiln.server.api.entity.CustomEntityBuilder
import dev.kiln.server.api.entity.CustomEntityType
import dev.kiln.server.api.entity.EntityContext
import dev.kiln.server.api.entity.GoalBuilder
import dev.kiln.server.api.entity.SequenceAction
import dev.kiln.server.runtime.extension.ExtensionImpl
import dev.kiln.server.runtime.item.ContentExtension
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import io.papermc.paper.event.player.PlayerUntrackEntityEvent
import net.kyori.adventure.key.Key
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.util.DefaultRandomPos
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
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
import java.util.EnumSet
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
    extensions: (String) -> ExtensionImpl?,
    private val send: (Player, ClientboundMessage) -> Unit,
) : CustomEntities, Listener {
    private val types = HashMap<Key, CustomEntityTypeImpl>()
    private val extensions = extensions
    private val live = HashMap<UUID, LiveEntity>()

    // ---- types --------------------------------------------------------------------------------------------

    override fun register(plugin: Plugin, key: Key, init: CustomEntityBuilder.() -> Unit): CustomEntityType {
        val definition = CustomEntityBuilder().apply(init)
        val model = definition.model ?: key
        val extension = extensions(key.namespace())?.let { ContentExtension(it.plugin.name, it.pkg.files.toSet()) }
            ?: throw IllegalArgumentException("No extension '${key.namespace()}' registered for ${key.asString()} (register the extension first)")
        require(extension.ownerName == plugin.name) { "${key.asString()}: extension '${key.namespace()}' belongs to ${extension.ownerName}" }
        val geo = "assets/${model.namespace()}/kiln/entity/${model.value()}.geo.json"
        require(model.namespace() == key.namespace() && geo in extension.files) { "Entity model ${model.asString()} not found (expected $geo)" }
        require(definition.width in 0.05..16.0 && definition.height in 0.05..16.0) { "entity size must be 0.05..16" }
        definition.behavior.states.keys.forEach { EntityNames.state(it) }
        check(key !in types) { "Custom entity ${key.asString()} is already registered" }
        val type = CustomEntityTypeImpl(this, plugin, key, model, definition)
        types[key] = type
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) restore(chunk) { it.type == key.asString() }
        return type
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
        val d = type.definition
        val mob = KilnMob(level)
        mob.size = EntityDimensions.scalable(d.width.toFloat(), d.height.toFloat())
        mob.setPos(location.x, location.y, location.z)
        mob.setYRot(location.yaw)
        mob.setYHeadRot(location.yaw)
        mob.setYBodyRot(location.yaw)
        stored?.let { mob.setUUID(it.id) }
        val bukkit = mob.bukkitEntity as Mob
        bukkit.isPersistent = false
        bukkit.removeWhenFarAway = false
        bukkit.isSilent = true
        bukkit.canPickupItems = false
        d.displayName?.let { bukkit.customName(it) }
        bukkit.getAttribute(Attribute.MAX_HEALTH)?.baseValue = d.health
        bukkit.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = d.speed
        bukkit.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = d.attackDamage
        bukkit.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = 0.0
        bukkit.health = (stored?.health ?: d.health).coerceIn(0.1, d.health)
        val entity = LiveEntity(this, type, mob, bukkit)
        stored?.let { entity.stateName = it.state }
        // registered before it enters the world: tracking (EntityAppearance) starts right away
        live[mob.uuid] = entity
        installGoals(entity)
        level.addFreshEntity(mob, CreatureSpawnEvent.SpawnReason.CUSTOM)
        if (stored == null) entity.hook("onSpawn") { d.onSpawn?.invoke(entity.context) }
        return entity
    }

    private fun installGoals(entity: LiveEntity) {
        val behavior = entity.type.definition.behavior
        behavior.targeting?.let { t -> entity.mob.addKilnTargetGoal(1, TargetGoal(entity, t.range, t.filter)) }
        for (goal in behavior.goals) entity.mob.addKilnGoal(goal.priority, BehaviorGoal(entity, goal))
    }

    // ---- animations ---------------------------------------------------------------------------------------

    internal fun play(entity: LiveEntity, name: String, playback: AnimationPlayback, speed: Float) {
        val mode = when (playback) {
            AnimationPlayback.ONCE -> AnimationMode.ONCE
            AnimationPlayback.LOOP -> AnimationMode.LOOP
            AnimationPlayback.HOLD -> AnimationMode.HOLD
        }
        // checked before storing: a stored bad message would fail for every later tracker
        val message = PlayAnimation(entity.mob.id, EntityNames.animation(name), mode, EntityNames.speed(speed))
        if (playback == AnimationPlayback.ONCE) entity.persistentAnimations.remove(name) else entity.persistentAnimations[name] = message
        entity.bukkit.trackedBy.forEach { send(it, message) }
    }

    internal fun stop(entity: LiveEntity, name: String?) {
        name?.let { EntityNames.animation(it) }
        if (name == null) entity.persistentAnimations.clear() else entity.persistentAnimations.remove(name)
        val message = StopAnimation(entity.mob.id, name)
        entity.bukkit.trackedBy.forEach { send(it, message) }
    }

    private fun appearance(entity: LiveEntity): EntityAppearance {
        val d = entity.type.definition
        return EntityAppearance(entity.mob.id, entity.type.model.asString(), d.width.toFloat(), d.height.toFloat(), d.headBone)
    }

    // ---- persistence --------------------------------------------------------------------------------------

    private fun chunkOf(location: Location) = location.world.getChunkAt(location.blockX shr 4, location.blockZ shr 4)

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
        val l = entity.bukkit.location
        return StoredEntity(entity.type.key.asString(), l.x, l.y, l.z, l.yaw, entity.bukkit.health, entity.stateName, entity.mob.uuid)
    }

    /**
     * Writes [entity] into the chunk it stands in (and takes its older entry out of the chunk it was written to
     * before); [remove] also takes it out of the world.
     */
    private fun store(entity: LiveEntity, remove: Boolean) {
        val chunk = chunkOf(entity.bukkit.location)
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
        live.values.filter { val l = it.bukkit.location; l.world == chunk.world && l.blockX shr 4 == chunk.x && l.blockZ shr 4 == chunk.z }
            .forEach { store(it, remove = true) }
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

    /** Every 10 ticks: ambient and step sounds. */
    fun tick() {
        for (entity in live.values) {
            val sounds = entity.type.definition.sounds
            if (!entity.bukkit.isValid) continue
            sounds.ambient?.let { if (Random.nextDouble() < AMBIENT_CHANCE) entity.sound(it) }
            sounds.step?.let { step ->
                val moving = entity.bukkit.pathfinder.hasPath() || entity.bukkit.velocity.clone().setY(0).lengthSquared() > 0.0004
                entity.stepTicks += 10
                if (moving && entity.bukkit.isOnGround && entity.stepTicks >= sounds.stepInterval) {
                    entity.stepTicks = 0
                    entity.sound(step, 0.6f)
                }
            }
        }
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
            val l = entity.bukkit.location
            if (l.world.isChunkLoaded(l.blockX shr 4, l.blockZ shr 4)) store(entity, remove = false)
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

    companion object {
        val CHUNK_KEY: NamespacedKey = NamespacedKey("kiln", "entities")

        /** Per 10 ticks: on average one ambient sound every 8 seconds. */
        private const val AMBIENT_CHANCE = 1.0 / 16
    }
}

class CustomEntityTypeImpl(
    private val manager: CustomEntityManager,
    override val plugin: Plugin,
    override val key: Key,
    val model: Key,
    val definition: CustomEntityBuilder,
) : CustomEntityType {
    override fun spawn(location: Location): CustomEntity = manager.spawn(this, location)

    override fun unregister() = manager.unregister(this)
}

data class StoredIn(val world: UUID, val chunkKey: Long)

/** One living custom entity and its behaviour state. */
class LiveEntity(
    private val manager: CustomEntityManager,
    override val type: CustomEntityTypeImpl,
    val mob: KilnMob,
    val bukkit: Mob,
) : CustomEntity {
    var stateName: String = "default"
    var stepTicks = 0

    /** Chunk the entity's entry was last written to (autosave), `null` = none. */
    var storedIn: StoredIn? = null
    val cooldowns = Cooldowns()
    val persistentAnimations = LinkedHashMap<String, PlayAnimation>()
    override val memory: MutableMap<String, Any> = HashMap()
    val context: EntityContext = Context()

    override val entity: Mob get() = bukkit

    override var state: String
        get() = stateName
        set(value) {
            if (value == stateName) return
            EntityNames.state(value)
            val states = type.definition.behavior.states
            states[stateName]?.onExit?.let { h -> hook("state $stateName exit") { h(context) } }
            stateName = value
            states[value]?.onEnter?.let { h -> hook("state $value enter") { h(context) } }
        }

    override val target: LivingEntity? get() = bukkit.target

    override fun playAnimation(name: String, playback: AnimationPlayback, speed: Float) = manager.play(this, name, playback, speed)

    override fun stopAnimation(name: String?) = manager.stop(this, name)

    fun sound(key: Key, volumeFactor: Float = 1f, pitch: Float? = null) {
        val sounds = type.definition.sounds
        bukkit.world.playSound(bukkit.location, key.asString(), org.bukkit.SoundCategory.HOSTILE, sounds.volume * volumeFactor,
            pitch ?: (sounds.pitch * (0.9f + Random.nextFloat() * 0.2f)))
    }

    internal inline fun hook(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            manager.log(this, what, e)
        }
    }

    private inner class Context : EntityContext {
        override val custom: CustomEntity get() = this@LiveEntity
        override val self: Mob get() = bukkit
        override val target: LivingEntity? get() = bukkit.target
        override val state: String get() = stateName
        override val memory: MutableMap<String, Any> get() = this@LiveEntity.memory

        override fun moveTo(location: Location, speed: Double): Boolean = bukkit.pathfinder.moveTo(location, speed)
        override fun moveTo(entity: Entity, speed: Double): Boolean =
            if (entity is LivingEntity) bukkit.pathfinder.moveTo(entity, speed) else bukkit.pathfinder.moveTo(entity.location, speed)

        override fun wanderTo(radius: Int, speed: Double): Boolean {
            val pos = DefaultRandomPos.getPos(mob, radius, 7) ?: return false
            return bukkit.pathfinder.moveTo(Location(bukkit.world, pos.x, pos.y, pos.z), speed)
        }

        override fun stopMoving() = bukkit.pathfinder.stopPathfinding()
        override fun isMoving(): Boolean = bukkit.pathfinder.hasPath()
        override fun lookAt(entity: Entity) = bukkit.lookAt(entity)
        override fun animate(name: String, playback: AnimationPlayback, speed: Float) = playAnimation(name, playback, speed)
        override fun damage(entity: LivingEntity, amount: Double) = entity.damage(amount, bukkit)

        override fun nearbyPlayers(range: Double): List<Player> =
            bukkit.world.getNearbyPlayers(bukkit.location, range).filter { it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE }
                .sortedBy { it.location.distanceSquared(bukkit.location) }

        override fun distanceTo(entity: Entity): Double = if (entity.world == bukkit.world) entity.location.distance(bukkit.location) else Double.MAX_VALUE
        override fun cooldownReady(name: String): Boolean = cooldowns.ready(name, bukkit.world.gameTime)
        override fun cooldown(name: String, ticks: Int) = cooldowns.start(name, ticks, bukkit.world.gameTime)
        override fun switchTo(state: String) {
            this@LiveEntity.state = state
        }

        override fun random(): Double = Random.nextDouble()
        override fun playSound(sound: Key, volume: Float, pitch: Float) =
            bukkit.world.playSound(bukkit.location, sound.asString(), org.bukkit.SoundCategory.HOSTILE, volume, pitch)
    }
}

/** A [GoalBuilder] on the NMS goal selector. An exception stops the goal for this entity for good. */
private class BehaviorGoal(private val entity: LiveEntity, private val def: GoalBuilder) : Goal() {
    private var broken = false
    private var runner: SequenceRunner<EntityContext>? = null

    init {
        val flags = EnumSet.noneOf(Flag::class.java)
        if (def.movement) flags += Flag.MOVE
        if (def.looking) flags += Flag.LOOK
        setFlags(flags)
    }

    private inline fun <T> guarded(fallback: T, what: String, block: () -> T): T {
        if (broken) return fallback
        return try {
            block()
        } catch (e: Throwable) {
            broken = true
            entity.hook("goal ${def.name} ($what)") { throw e }
            fallback
        }
    }

    private fun inState(): Boolean = def.states?.contains(entity.stateName) ?: true

    override fun canUse(): Boolean = inState() && guarded(false, "canStart") { def.canStart(entity.context) }

    override fun canContinueToUse(): Boolean {
        if (!inState() || broken) return false
        runner?.let { return !it.finished }
        return guarded(false, "canContinue") { (def.canContinue ?: def.canStart)(entity.context) }
    }

    override fun start() {
        runner = def.sequence?.let { steps ->
            SequenceRunner(steps.map { step ->
                when (step) {
                    is SequenceAction.Run -> SequenceStep.Run<EntityContext> { ctx -> step.action(ctx) }
                    is SequenceAction.Wait -> SequenceStep.Wait(step.ticks)
                }
            })
        }
        guarded(Unit, "onStart") { def.onStart?.invoke(entity.context) }
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        guarded(Unit, "tick") {
            def.onTick?.invoke(entity.context)
            runner?.tick(entity.context)
        }
    }

    override fun stop() {
        runner = null
        guarded(Unit, "onStop") { def.onStop?.invoke(entity.context) }
    }
}

/** Picks the nearest visible survival/adventure player in range as target (checked every 10 ticks). */
private class TargetGoal(private val entity: LiveEntity, private val range: Double, private val filter: (Player) -> Boolean) : Goal() {
    private var cooldown = 0

    init {
        setFlags(EnumSet.of(Flag.TARGET))
    }

    override fun canUse(): Boolean {
        if (--cooldown > 0) return false
        cooldown = 10
        val mob = entity.bukkit
        val current = mob.target
        if (current != null && current.isValid && current.location.world == mob.world && current.location.distance(mob.location) <= range) return false
        val candidate = entity.context.nearbyPlayers(range).firstOrNull { mob.hasLineOfSight(it) && runCatching { filter(it) }.getOrDefault(false) }
        mob.target = candidate
        return false
    }
}
