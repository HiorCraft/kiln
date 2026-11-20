package dev.kiln.client.mixin;

import java.util.Map;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the global name -> mapping registry, needed to remove server keybinds again on disconnect. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("ALL")
	static Map<String, KeyMapping> kiln$getAll() {
		throw new AssertionError();
	}
}
