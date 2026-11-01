package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Trial spawners and the entity limit: a refused mob is never added, and the spawner tries again later. */
@Mixin(TrialSpawner.class)
abstract class TrialSpawnerMixin {
	@WrapOperation(
		method = "spawnMob",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z")
	)
	private boolean tickengine$limitEntities(ServerLevel level, Entity entity, Operation<Boolean> original) {
		return EntityLimit.allows(level, entity.getType(), entity.blockPosition(), EntityLimit.Source.TRIAL_SPAWNER)
			&& original.call(level, entity);
	}
}
