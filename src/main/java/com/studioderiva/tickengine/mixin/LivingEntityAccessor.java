package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the minimal tick advance the idle time that drives random despawning. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("noActionTime")
	int tickengine$getNoActionTime();

	@Accessor("noActionTime")
	void tickengine$setNoActionTime(int noActionTime);
}
