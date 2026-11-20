package dev.kiln.client.mixin;

import dev.kiln.client.resource.KilnPostEffect;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * A server post effect that fails to compile must not start Minecraft's resource-pack recovery (which disables the
 * server's packs and disconnects): while the effect is checked (KilnPostEffect.request) or loaded again later (e.g.
 * after a resource reload) recovery is skipped on that thread, and an effect that no longer loads is switched off.
 */
@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
	@Unique
	private final ThreadLocal<Boolean> kiln$guarding = ThreadLocal.withInitial(() -> false);

	@Inject(method = "getPostChain", at = @At("HEAD"))
	private void kiln$beginServerEffect(Identifier id, Set<Identifier> targets, CallbackInfoReturnable<PostChain> cir) {
		kiln$guarding.set(KilnPostEffect.INSTANCE.beginLoad(id));
	}

	@Inject(method = "getPostChain", at = @At("RETURN"))
	private void kiln$endServerEffect(Identifier id, Set<Identifier> targets, CallbackInfoReturnable<PostChain> cir) {
		if (kiln$guarding.get()) {
			kiln$guarding.set(false);
			KilnPostEffect.INSTANCE.endLoad(cir.getReturnValue() != null);
		}
	}

	@Inject(method = "tryTriggerRecovery", at = @At("HEAD"), cancellable = true)
	private void kiln$noRecoveryWhileProbing(Exception exception, CallbackInfo ci) {
		if (KilnPostEffect.INSTANCE.getProbing()) ci.cancel();
	}
}
