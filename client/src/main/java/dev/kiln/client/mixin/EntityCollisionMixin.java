package dev.kiln.client.mixin;

import dev.kiln.client.entity.KilnEntityTraits;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Solid furniture hit boxes: interaction entities the server marked solid collide like boats, matching the server's
 * KilnInteraction, so client movement and server checks agree.
 */
@Mixin(Entity.class)
abstract class EntityCollisionMixin {
	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void kiln$solidFurniture(Entity other, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self instanceof Interaction && KilnEntityTraits.CLIENT.isSolid(self.getId())) cir.setReturnValue(true);
	}
}
