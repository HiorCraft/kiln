package dev.kiln.client.mixin;

import dev.kiln.client.entity.KilnModelRenderState;
import dev.kiln.client.entity.KilnModelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities with a Kiln appearance are drawn by KilnModelRenderer. Vanilla looks the renderer up by entity (extract)
 * and again by render state (submit); both lookups must agree.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
	@Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;", at = @At("HEAD"), cancellable = true)
	private void kiln$rendererForEntity(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		KilnModelRenderer renderer = KilnModelRenderer.forEntity(entity);
		if (renderer != null) cir.setReturnValue(renderer);
	}

	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;", at = @At("HEAD"), cancellable = true)
	private void kiln$rendererForState(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (state instanceof KilnModelRenderState && KilnModelRenderer.getInstance() != null) cir.setReturnValue(KilnModelRenderer.getInstance());
	}
}
