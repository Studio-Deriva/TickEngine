package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
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
 *
 * <p>Block profiling: scheduled block and fluid ticks (redstone components, falling blocks, flowing
 * water and lava), block events (pistons, note blocks) and random ticks (crops, leaves, ice) are
 * timed per block or fluid while a tick is sampled. A scheduled tick includes the block updates it
 * causes, so a repeater's time includes the redstone dust it powers.
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
		boolean marked = mode == EntityThrottle.Mode.AI_SKIPPED || mode == EntityThrottle.Mode.THROTTLED_FULL;
		if (marked) {
			EntityThrottle.setCurrent(entity, mode);
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
			if (marked) {
				EntityThrottle.setCurrent(null, EntityThrottle.Mode.FULL);
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

	@WrapMethod(method = "tickBlock")
	private void tickengine$timeBlockTick(BlockPos pos, Block block, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(pos, block);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(pos, block);
		} finally {
			TickProfiler.record(ProfileSnapshot.Category.BLOCK_TICK, block, System.nanoTime() - start, (ServerLevel) (Object) this, pos);
		}
	}

	@WrapMethod(method = "tickFluid")
	private void tickengine$timeFluidTick(BlockPos pos, Fluid fluid, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(pos, fluid);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(pos, fluid);
		} finally {
			TickProfiler.record(ProfileSnapshot.Category.FLUID_TICK, fluid, System.nanoTime() - start, (ServerLevel) (Object) this, pos);
		}
	}

	@WrapMethod(method = "doBlockEvent")
	private boolean tickengine$timeBlockEvent(BlockEventData event, Operation<Boolean> original) {
		if (!TickProfiler.sampling) {
			return original.call(event);
		}
		long start = System.nanoTime();
		try {
			return original.call(event);
		} finally {
			TickProfiler.record(ProfileSnapshot.Category.BLOCK_EVENT, event.block(), System.nanoTime() - start,
				(ServerLevel) (Object) this, event.pos());
		}
	}

	// require = 0: mods that rewrite random ticking (none known) lose this row instead of crashing the server.
	@WrapOperation(
		method = "tickChunk",
		require = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V")
	)
	private void tickengine$timeRandomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
		Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(state, level, pos, random);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(state, level, pos, random);
		} finally {
			TickProfiler.record(ProfileSnapshot.Category.RANDOM_TICK, state.getBlock(), System.nanoTime() - start, level, pos);
		}
	}

	@WrapOperation(
		method = "tickChunk",
		require = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V")
	)
	private void tickengine$timeFluidRandomTick(FluidState state, net.minecraft.world.level.Level level, BlockPos pos,
		RandomSource random, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(state, level, pos, random);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(state, level, pos, random);
		} finally {
			TickProfiler.record(ProfileSnapshot.Category.RANDOM_TICK, state.getType(), System.nanoTime() - start, level, pos);
		}
	}
}
