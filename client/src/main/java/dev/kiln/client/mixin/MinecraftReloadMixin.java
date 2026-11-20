package dev.kiln.client.mixin;

import dev.kiln.client.resource.KilnPackSource;
import dev.kiln.client.resource.KilnResourceReload;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A failed reload with the server's extension pack: drop that pack and reload without it instead of vanilla's
 * rollback (which would clear the player's own resource packs and disconnect without a reason).
 */
@Mixin(Minecraft.class)
abstract class MinecraftReloadMixin {
	@Inject(method = "rollbackResourcePacks", at = @At("HEAD"), cancellable = true)
	private void kiln$extensionPackFailed(Throwable error, GameLoadCookie cookie, CallbackInfo ci) {
		Minecraft self = (Minecraft) (Object) this;
		boolean ours = KilnResourceReload.CLIENT.failed(error, () -> {
			KilnPackSource.INSTANCE.clear();
			self.reloadResourcePacks();
			return kotlin.Unit.INSTANCE;
		});
		if (ours) ci.cancel();
	}
}
