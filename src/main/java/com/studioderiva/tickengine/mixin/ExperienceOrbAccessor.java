package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the minimal tick age experience orbs, so they still despawn after 5 minutes. */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccessor {
	@Accessor("age")
	int tickengine$getAge();

	@Accessor("age")
	void tickengine$setAge(int age);
}
