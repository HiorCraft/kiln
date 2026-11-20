package dev.kiln.client.mixin;

import dev.kiln.client.resource.KilnPostEffect;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GameRenderer.update rebuilds the requested post effects every frame; the server's effect is added after it. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
	private void kiln$addServerPostEffect(DeltaTracker deltaTracker, CallbackInfo ci) {
		Identifier effect = KilnPostEffect.INSTANCE.getCurrent();
		if (effect != null) ((GameRenderer) (Object) this).getRequestedPostEffects().add(effect);
	}
}
