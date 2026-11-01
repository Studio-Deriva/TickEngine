package com.studioderiva.tickengine.mixin;

import com.studioderiva.tickengine.limit.EntityLimit;
import com.studioderiva.tickengine.stacking.EntityStacker;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Natural spawning: a stack fills the mob cap like all the mobs it holds, emergency mode can pause
 * spawning, and the entity limit stops mobs in full chunks. The first two hooks use only the
 * parameters they need, since the other ones changed in 1.21.5.
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

	/** Last check before a natural mob is added: a chunk at the limit gets no more of its type. */
	@Inject(method = "isValidPositionForMob", at = @At("RETURN"), cancellable = true)
	private static void tickengine$limitEntities(ServerLevel level, Mob mob, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !EntityLimit.allows(level, mob.getType(), mob.blockPosition(), EntityLimit.Source.NATURAL)) {
			cir.setReturnValue(false);
		}
	}
}
