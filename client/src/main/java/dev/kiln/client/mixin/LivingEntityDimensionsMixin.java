package dev.kiln.client.mixin;

import dev.kiln.client.entity.AppearanceEntry;
import dev.kiln.client.entity.KilnEntityAppearances;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom entities use the hit box the server announced (same as its KilnMob), so aiming at them matches. */
@Mixin(LivingEntity.class)
abstract class LivingEntityDimensionsMixin {
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void kiln$customSize(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		// also called from entity constructors, before an id is assigned (getId() would throw)
		if (KilnEntityAppearances.INSTANCE.isEmpty()) return;
		AppearanceEntry entry = KilnEntityAppearances.INSTANCE.get(((EntityIdAccessor) this).kiln$rawId());
		if (entry != null) cir.setReturnValue(EntityDimensions.scalable(entry.getAppearance().getWidth(), entry.getAppearance().getHeight()));
	}
}
