package dev.kiln.server.runtime.entity

import dev.kiln.protocol.message.PlayAnimation
import dev.kiln.server.api.entity.AnimationPlayback
import dev.kiln.server.api.entity.CustomEntity
import dev.kiln.server.api.entity.EntityContext
import net.kyori.adventure.key.Key
import org.bukkit.Chunk
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import kotlin.random.Random

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
    val context: EntityContext = LiveEntityContext(this)

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

    /** Plays [key] with the definition's volume (times [volumeFactor]) and a slightly varied pitch. */
    fun sound(key: Key, volumeFactor: Float = 1f) {
        val sounds = type.definition.sounds
        playSound(key, sounds.volume * volumeFactor, sounds.pitch * (PITCH_MIN_FACTOR + Random.nextFloat() * PITCH_SPREAD))
    }

    fun playSound(key: Key, volume: Float, pitch: Float) =
        bukkit.world.playSound(bukkit.location, key.asString(), SoundCategory.HOSTILE, volume, pitch)

    internal inline fun hook(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            manager.log(this, what, e)
        }
    }

    private companion object {
        const val PITCH_MIN_FACTOR = 0.9f
        const val PITCH_SPREAD = 0.2f
    }
}
