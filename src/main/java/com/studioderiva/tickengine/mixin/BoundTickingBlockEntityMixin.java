package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every ticking block entity (vanilla and modded) goes through this class.
 * <ul>
 *   <li>Throttling: skips the whole tick when {@link BlockEntityThrottle} says so.</li>
 *   <li>Profiling: times the ticker call while a tick is being sampled. Only the ticker call
 *   is wrapped, so validity checks done by vanilla are not counted.</li>
 * </ul>
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
abstract class BoundTickingBlockEntityMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void tickengine$throttle(CallbackInfo ci) {
		if (BlockEntityThrottle.shouldSkip(blockEntity)) {
			ci.cancel();
		}
	}

	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V")
	)
	private void tickengine$timeTicker(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos,
		BlockState state, BlockEntity blockEntity, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(ticker, level, pos, state, blockEntity);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(ticker, level, pos, state, blockEntity);
		} finally {
			TickProfiler.recordBlockEntity(blockEntity, System.nanoTime() - start);
		}
	}
}
