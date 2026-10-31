package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.TickProfiler;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunk unloading and saving, and chunks that start generating because nothing is saved for them. */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
	@WrapMethod(method = "tick(Ljava/util/function/BooleanSupplier;)V")
	private void tickengine$timeUnloads(BooleanSupplier hasMoreTime, Operation<Void> original) {
		if (!TickProfiler.sampling) {
			original.call(hasMoreTime);
			return;
		}
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		try {
			original.call(hasMoreTime);
		} finally {
			TickProfiler.endChunkWork(ChunkWork.UNLOAD, start, inner);
		}
	}

	@Inject(method = "createEmptyChunk", at = @At("HEAD"))
	private void tickengine$countNewChunk(ChunkPos pos, CallbackInfoReturnable<?> cir) {
		TickProfiler.chunkCreated();
	}
}
