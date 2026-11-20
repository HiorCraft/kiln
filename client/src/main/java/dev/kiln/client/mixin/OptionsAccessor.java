package dev.kiln.client.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Makes the (final) key mapping array replaceable so server keybinds can be added/removed at runtime. */
@Mixin(Options.class)
public interface OptionsAccessor {
	@Mutable
	@Accessor("keyMappings")
	void kiln$setKeyMappings(KeyMapping[] keyMappings);
}
