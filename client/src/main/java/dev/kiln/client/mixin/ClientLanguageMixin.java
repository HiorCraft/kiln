package dev.kiln.client.mixin;

import dev.kiln.client.keybind.DynamicTranslations;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Display names of server keybinds and their categories, which only exist at runtime (see DynamicTranslations). */
@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin {
	@Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
	private void kiln$getOrDefault(String key, String defaultValue, CallbackInfoReturnable<String> cir) {
		String value = DynamicTranslations.get(key);
		if (value != null) {
			Language self = (Language) (Object) this;
			// a server may send a translation key as name; resolve it if the loaded language knows it
			cir.setReturnValue(DynamicTranslations.get(value) == null && self.has(value) ? self.getOrDefault(value, value) : value);
		}
	}

	@Inject(method = "has(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
	private void kiln$has(String key, CallbackInfoReturnable<Boolean> cir) {
		if (DynamicTranslations.get(key) != null) {
			cir.setReturnValue(true);
		}
	}
}
