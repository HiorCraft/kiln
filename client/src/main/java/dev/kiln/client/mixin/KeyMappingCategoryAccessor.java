package dev.kiln.client.mixin;

import java.util.List;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the category registry, needed to unregister the per-server categories on disconnect. */
@Mixin(KeyMapping.Category.class)
public interface KeyMappingCategoryAccessor {
	@Accessor("SORT_ORDER")
	static List<KeyMapping.Category> kiln$getSortOrder() {
		throw new AssertionError();
	}
}
