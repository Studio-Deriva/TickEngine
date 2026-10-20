package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Times entity ticks (including riding entities) while a tick is being sampled. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapOperation(
		method = "tickNonPassenger",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V")
	)
	private void tickengine$timeEntityTick(Entity entity, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(entity);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(entity);
		} finally {
			TickProfiler.recordEntity(entity, System.nanoTime() - start);
		}
	}

	@WrapOperation(
		method = "tickPassenger",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V")
	)
	private void tickengine$timePassengerTick(Entity passenger, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(passenger);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(passenger);
		} finally {
			TickProfiler.recordEntity(passenger, System.nanoTime() - start);
		}
	}
}
