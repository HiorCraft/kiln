package dev.kiln.client.entity

import dev.kiln.client.entity.model.BonePose
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState

class KilnModelRenderState : LivingEntityRenderState() {
    var entityId: Int = 0
    var model: LoadedModel? = null
    var poses: Map<String, BonePose> = emptyMap()
    var hurt: Boolean = false

    /** Ticks since death (with partial tick), 0 while alive; tips over like vanilla unless the model has `death`. */
    var dying: Float = 0f
    var tipOver: Boolean = false
}
