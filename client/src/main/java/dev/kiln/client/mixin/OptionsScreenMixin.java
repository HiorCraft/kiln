package dev.kiln.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.kiln.client.ext.trust.TrustManagementScreen;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Server-Erweiterungen…" to the options screen as an extra row of its button grid (spanning both columns,
 * as wide as the two buttons above it), right before the grid is added to the layout. The grid stays centered in
 * the content area of the header/footer layout, so no vanilla button is covered.
 */
@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Inject(
		method = "init()V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"
		)
	)
	private void kiln$addExtensionsButton(CallbackInfo ci, @Local GridLayout.RowHelper helper) {
		// two columns of 150 px buttons with 4 px padding on each side -> 308 px spans both exactly
		helper.addChild(TrustManagementScreen.entryButton(this, 308), 2);
	}
}
