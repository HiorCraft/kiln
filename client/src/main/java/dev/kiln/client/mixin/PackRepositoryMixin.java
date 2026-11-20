package dev.kiln.client.mixin;

import com.google.common.collect.ImmutableMap;
import dev.kiln.client.resource.KilnPackSource;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Offers the extension resources of the current server (KilnPackSource) in the client's resource pack repository
 * only (not in data pack repositories of the integrated server). The pack is required, so the repository selects it
 * on its own while it is available and drops it once KilnPackSource is cleared.
 */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {
	@Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
	private void kiln$addExtensionPack(CallbackInfoReturnable<Map<String, Pack>> cir) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.getResourcePackRepository() != (Object) this) return;
		Map<String, Pack> packs = new TreeMap<>(cir.getReturnValue());
		KilnPackSource.INSTANCE.loadPacks(pack -> packs.put(pack.getId(), pack));
		cir.setReturnValue(ImmutableMap.copyOf(packs));
	}
}
