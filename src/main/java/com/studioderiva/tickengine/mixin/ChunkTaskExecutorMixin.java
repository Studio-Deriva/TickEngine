package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.ChunkTaskExecutor;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.TickProfiler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Chunk tasks the server thread runs for one dimension: turning loaded and generated chunks into
 * full chunks, reading saved chunks (timed apart, see {@link ChunkDataReadMixin}) and similar work.
 * They run inside ticks (while the server waits for a chunk, or in the chunk source tick) and
 * between ticks, when the server has time to spare.
 *
 * <p>Waiting for a chunk is timed in {@link BlockableEventLoopMixin}; the marker interface tells
 * this executor apart there.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
abstract class ChunkTaskExecutorMixin implements ChunkTaskExecutor {
	@WrapMethod(method = "doRunTask")
	private void tickengine$timeChunkTask(Runnable task, Operation<Void> original) {
		if (!TickProfiler.sampling && !TickProfiler.collecting) {
			original.call(task);
			return;
		}
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		try {
			original.call(task);
		} finally {
			TickProfiler.endChunkWork(ChunkWork.TASKS, start, inner);
		}
	}
}
