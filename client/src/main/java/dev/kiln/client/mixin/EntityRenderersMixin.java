package dev.kiln.client.mixin;

import dev.kiln.client.entity.KilnModelRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Builds Kiln's bone-model renderer together with vanilla's entity renderers (same context, rebuilt on reload). */
@Mixin(EntityRenderers.class)
abstract class EntityRenderersMixin {
	@Inject(method = "createEntityRenderers", at = @At("HEAD"))
	private static void kiln$createModelRenderer(EntityRendererProvider.Context context, CallbackInfoReturnable<Map<?, ?>> cir) {
		KilnModelRenderer.setInstance(new KilnModelRenderer(context));
	}
}
