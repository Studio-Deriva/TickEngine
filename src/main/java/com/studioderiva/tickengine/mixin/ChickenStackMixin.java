package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A stack of chickens lays as many eggs as its chickens would: when it has just laid one, the wait for
 * the next is divided by the count. Named by text because the class moved in 1.21.11.
 */
//? if >=1.21.11 {
@Mixin(targets = "net.minecraft.world.entity.animal.chicken.Chicken")
//?} else {
/*@Mixin(targets = "net.minecraft.world.entity.animal.Chicken")
*///?}
abstract class ChickenStackMixin {
	@Shadow
	public int eggTime;

	@Unique
	private int tickengine$eggTimeBefore;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void tickengine$beforeAiStep(CallbackInfo ci) {
		tickengine$eggTimeBefore = eggTime;
	}

	@Inject(method = "aiStep", at = @At("TAIL"))
	private void tickengine$afterAiStep(CallbackInfo ci) {
		// The timer only goes up when an egg was just laid (or tried, without the loot table giving one).
		if (eggTime > tickengine$eggTimeBefore) {
			int count = EntityStacker.count((Entity) (Object) this);
			if (count > 1) {
				eggTime = Math.max(1, eggTime / count);
			}
		}
	}
}
