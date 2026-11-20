package dev.kiln.client.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clearing the cached parameters makes the creative screen rebuild all tab contents (server tabs changed). */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {
	@Accessor("CACHED_PARAMETERS")
	static void kiln$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
		throw new AssertionError();
	}
}
