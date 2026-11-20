package dev.kiln.client.entity

import com.mojang.blaze3d.vertex.PoseStack
import dev.kiln.client.entity.model.AnimationMixer
import dev.kiln.client.entity.model.BonePose
import dev.kiln.client.entity.model.GeoMesh
import dev.kiln.client.entity.model.Vec3d
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.entity.LivingEntity

class KilnModelRenderState : LivingEntityRenderState() {
    var entityId: Int = 0
    var model: KilnModels.Loaded? = null
    var poses: Map<String, BonePose> = emptyMap()
    var hurt: Boolean = false
}

/**
 * Draws living entities that have an `EntityAppearance` with their Bedrock bone model: state animation (idle/walk
 * from the movement) plus the server's animations, the `head` bone following the look direction, red when hurt.
 */
class KilnModelRenderer(context: EntityRendererProvider.Context) : EntityRenderer<LivingEntity, KilnModelRenderState>(context) {
    override fun createRenderState(): KilnModelRenderState = KilnModelRenderState()

    override fun extractRenderState(entity: LivingEntity, state: KilnModelRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        val entry = KilnEntityAppearances.get(entity.id)
        val model = entry?.let { KilnModels.get(it.appearance.model) }
        state.entityId = entity.id
        state.model = model
        state.bodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        state.yRot = Mth.wrapDegrees(Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - state.bodyRot)
        state.xRot = entity.getXRot(partialTick)
        state.walkAnimationSpeed = entity.walkAnimation.speed(partialTick)
        state.hurt = entity.hurtTime > 0 || entity.deathTime > 0
        if (model == null || entry == null) {
            state.poses = emptyMap()
            return
        }
        val now = Util.getMillis() / 1000.0
        val walking = state.walkAnimationSpeed > 0.05f
        val mixed = AnimationMixer.mix(model.animations, if (walking) "walk" else "idle", state.ageInTicks / 20.0, entry.animations.toList(), now)
        entry.animations.removeAll(mixed.finished.toSet())
        val poses = HashMap(mixed.poses)
        if (entry.appearance.headBone) {
            val head = poses["head"] ?: BonePose()
            poses["head"] = head.copy(rotation = head.rotation + Vec3d(-state.xRot.toDouble(), -state.yRot.toDouble(), 0.0))
        }
        state.poses = poses
        state.shadowRadius = entry.appearance.width / 2f
    }

    override fun submit(state: KilnModelRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val model = state.model ?: return
        poseStack.pushPose()
        poseStack.mulPose(org.joml.Matrix4f().rotateY(Math.toRadians(180.0 - state.bodyRot).toFloat()))
        poseStack.scale(1f / 16f, 1f / 16f, 1f / 16f)
        val light = state.lightCoords
        val overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(state.hurt))
        val poses = state.poses
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(model.texture)) { pose, consumer ->
            GeoMesh.emit(model.geo, poses) { x, y, z, u, v, nx, ny, nz ->
                consumer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz)
            }
        }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    companion object {
        /** Created when vanilla builds its entity renderers (`EntityRenderersMixin`). */
        @JvmStatic
        var instance: KilnModelRenderer? = null

        /** The renderer for [entity] if it has a loadable Kiln appearance, else `null` (vanilla renderer). */
        @JvmStatic
        fun forEntity(entity: Any): KilnModelRenderer? {
            if (entity !is LivingEntity) return null
            val entry = KilnEntityAppearances.get(entity.id) ?: return null
            if (KilnModels.get(entry.appearance.model) == null) return null
            return instance
        }
    }
}
