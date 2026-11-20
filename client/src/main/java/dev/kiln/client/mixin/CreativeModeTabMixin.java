package dev.kiln.client.mixin;

import dev.kiln.client.creative.KilnCreativeTabs;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Title and icon of Kiln's placeholder tabs come from the server (vanilla caches the icon once). */
@Mixin(CreativeModeTab.class)
abstract class CreativeModeTabMixin {
	@Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
	private void kiln$title(CallbackInfoReturnable<Component> cir) {
		Component title = KilnCreativeTabs.title((CreativeModeTab) (Object) this);
		if (title != null) cir.setReturnValue(title);
	}

	@Inject(method = "getIconItem", at = @At("HEAD"), cancellable = true)
	private void kiln$icon(CallbackInfoReturnable<ItemStack> cir) {
		ItemStack icon = KilnCreativeTabs.icon((CreativeModeTab) (Object) this);
		if (icon != null) cir.setReturnValue(icon);
	}
}
