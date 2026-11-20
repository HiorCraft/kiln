package dev.kiln.server.runtime.entity

import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.level.Level

/**
 * The server side of a custom entity: a zombie on the network (clients spawn a zombie; the Kiln runtime draws the
 * bone model instead), without any zombie behaviour: no goals, no sunburn, no drowning conversion, no baby, no
 * reinforcements (sounds off via the Bukkit silent flag), and the hit box of its definition. Kiln adds its goals ([addKilnGoal]). Not saved
 * (custom entities live in their chunk's data).
 */
class KilnMob(level: Level) : Zombie(net.minecraft.world.entity.EntityTypes.ZOMBIE, level) {
    /** Set right after construction (vanilla calls [getDefaultDimensions] from the constructor already). */
    var size: EntityDimensions? = null
        set(value) {
            field = value
            refreshDimensions()
        }

    override fun registerGoals() {}

    override fun addBehaviourGoals() {}

    override fun isSunSensitive(): Boolean = false

    // Mob.aiStep burns every type tagged burn_in_daylight (zombie is) through this check, not isSunSensitive
    override fun isSunBurnTick(): Boolean = false

    override fun canPickUpLoot(): Boolean = false

    override fun convertsInWater(): Boolean = false

    override fun isBaby(): Boolean = false

    override fun getDefaultDimensions(pose: Pose): EntityDimensions = size ?: super.getDefaultDimensions(pose)

    fun addKilnGoal(priority: Int, goal: Goal) = goalSelector.addGoal(priority, goal)

    fun addKilnTargetGoal(priority: Int, goal: Goal) = targetSelector.addGoal(priority, goal)
}
