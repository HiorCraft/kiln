package dev.kiln.client.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The raw id field: getId() throws while the entity is still being constructed (no id assigned yet). */
@Mixin(Entity.class)
public interface EntityIdAccessor {
	@Accessor("id")
	int kiln$rawId();
}
