package dev.kiln.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.kiln.client.ext.trust.TrustManagementScreen;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a full-width "Server-Erweiterungen…" row to the pause menu, directly above "Disconnect", while the client is
 * in the world of a kiln server. It is a row of the menu's own grid, so the grid grows and is re-centered by
 * vanilla instead of the button covering anything.
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(
		method = "createPauseMenu()V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/network/chat/CommonComponents;disconnectButtonLabel(Z)Lnet/minecraft/network/chat/Component;"
		)
	)
	private void kiln$addExtensionsButton(CallbackInfo ci, @Local GridLayout.RowHelper helper) {
		if (TrustManagementScreen.showInPauseMenu()) {
			helper.addChild(TrustManagementScreen.entryButton(this, 204), 2);
		}
	}
}
