package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Natural spawning: a stack fills the mob cap like all the mobs it holds, and emergency mode
 * can pause spawning. Only the parameters both hooks need are used, as in the Fabric version.
 */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerMixin {
	@ModifyVariable(method = "createState", at = @At("HEAD"), argsOnly = true)
	private static Iterable<Entity> tickengine$countStacks(Iterable<Entity> entities) {
		return EntityStacker.expandStacks(entities);
	}

	@Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
	private static void tickengine$pauseSpawning(CallbackInfo ci) {
		if (EmergencyMode.pausesSpawning()) {
			ci.cancel();
		}
	}
}
