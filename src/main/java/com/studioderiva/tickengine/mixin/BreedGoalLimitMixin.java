package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeding through goals and the entity limit. Foxes and turtles have their own {@code breed}: foxes
 * make the cub themselves, turtles get an egg to lay. A refused pair loses its love mode, as in
 * {@link AnimalBreedingMixin}. The classes moved to their own packages in 1.21.11.
 */
//? if >=1.21.11 {
@Mixin(value = BreedGoal.class, targets = {
	"net.minecraft.world.entity.animal.fox.Fox$FoxBreedGoal",
	"net.minecraft.world.entity.animal.turtle.Turtle$TurtleBreedGoal"
})
//?} else {
/*@Mixin(value = BreedGoal.class, targets = {
	"net.minecraft.world.entity.animal.Fox$FoxBreedGoal",
	"net.minecraft.world.entity.animal.Turtle$TurtleBreedGoal"
})
*///?}
abstract class BreedGoalLimitMixin {
	@Inject(method = "breed", at = @At("HEAD"), cancellable = true)
	private void tickengine$limitEntities(CallbackInfo ci) {
		BreedGoalAccessor goal = (BreedGoalAccessor) this;
		if (!EntityLimit.allowsBreeding(goal.tickengine$getAnimal(), goal.tickengine$getPartner())) {
			ci.cancel();
			return;
		}
		EntityLimit.setBreedingChecked(true);
	}

	@Inject(method = "breed", at = @At("RETURN"))
	private void tickengine$breedingDone(CallbackInfo ci) {
		EntityLimit.setBreedingChecked(false);
	}
}
