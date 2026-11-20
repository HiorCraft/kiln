package dev.kiln.client.mixin;

import dev.kiln.client.resource.KilnPostEffect;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server post effect that fails to compile must not start Minecraft's resource-pack recovery (which disables the
 * server's packs and disconnects); KilnPostEffect checks the effect first and ignores it instead.
 */
@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
	@Inject(method = "tryTriggerRecovery", at = @At("HEAD"), cancellable = true)
	private void kiln$noRecoveryWhileProbing(Exception exception, CallbackInfo ci) {
		if (KilnPostEffect.INSTANCE.getProbing()) ci.cancel();
	}
}
