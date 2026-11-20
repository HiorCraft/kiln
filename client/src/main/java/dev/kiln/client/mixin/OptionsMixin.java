package dev.kiln.client.mixin;

import dev.kiln.client.keybind.ServerKeybinds;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server keybinds are persisted in kiln's own file (per server) instead of options.txt: before vanilla
 * writes options.txt they are persisted and temporarily taken out of {@link Options#keyMappings}.
 */
@Mixin(Options.class)
abstract class OptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Unique
	private KeyMapping[] kiln$allKeyMappings;

	@Inject(method = "save()V", at = @At("HEAD"))
	private void kiln$beforeSave(CallbackInfo ci) {
		ServerKeybinds.onOptionsSave();
		KeyMapping[] withoutServer = ServerKeybinds.withoutServerMappings(keyMappings);
		if (withoutServer != keyMappings) {
			kiln$allKeyMappings = keyMappings;
			keyMappings = withoutServer;
		}
	}

	@Inject(method = "save()V", at = @At("RETURN"))
	private void kiln$afterSave(CallbackInfo ci) {
		if (kiln$allKeyMappings != null) {
			keyMappings = kiln$allKeyMappings;
			kiln$allKeyMappings = null;
		}
	}
}
