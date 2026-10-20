package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entity ticks go through here.
 * <ul>
 *   <li>Throttling: {@link EntityThrottle} decides whether the tick runs fully, runs with
 *   its AI skipped, or is replaced by a minimal tick.</li>
 *   <li>Profiling: times entity ticks (including riding entities) while a tick is sampled.
 *   Minimal ticks are not timed.</li>
 * </ul>
 * Passengers are never throttled: their vehicle is excluded by default and they ride along.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@WrapOperation(
		method = "tickNonPassenger",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V")
	)
	private void tickengine$tickEntity(Entity entity, Operation<Void> original) {
		EntityThrottle.Mode mode = EntityThrottle.decide(entity);
		if (mode == EntityThrottle.Mode.MINIMAL) {
			EntityThrottle.minimalTick(entity);
			return;
		}
		if (mode == EntityThrottle.Mode.AI_SKIPPED) {
			EntityThrottle.setAiSkipped(entity);
		}
		try {
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
		} finally {
			if (mode == EntityThrottle.Mode.AI_SKIPPED) {
				EntityThrottle.setAiSkipped(null);
			}
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
