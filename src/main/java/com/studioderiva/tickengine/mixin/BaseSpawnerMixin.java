package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.limit.EntityLimit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Monster spawners and the entity limit: a mob refused here is never added, and the spawner waits its
 * normal delay before trying again, as when too many mobs are around it.
 */
@Mixin(BaseSpawner.class)
abstract class BaseSpawnerMixin {
	@WrapOperation(
		method = "serverTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z")
	)
	private boolean tickengine$limitEntities(ServerLevel level, Entity entity, Operation<Boolean> original) {
		return EntityLimit.allows(level, entity.getType(), entity.blockPosition(), EntityLimit.Source.SPAWNER)
			&& original.call(level, entity);
	}
}
