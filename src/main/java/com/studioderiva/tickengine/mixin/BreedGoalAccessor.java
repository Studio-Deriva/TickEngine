package com.studioderiva.tickengine.mixin;

import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the entity limit see the two animals of a breeding goal. */
@Mixin(BreedGoal.class)
public interface BreedGoalAccessor {
	@Accessor("animal")
	Animal tickengine$getAnimal();

	@Accessor("partner")
	Animal tickengine$getPartner();
}
