package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.ChunkTaskExecutor;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.TickProfiler;
import java.util.function.BooleanSupplier;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times the server thread waiting for a chunk it asked for (an entity or a mod reading a chunk that
 * is not loaded yet), when the waiting executor is the chunk source's. Chunk tasks run while it waits
 * are counted as tasks, not waiting.
 */
@Mixin(BlockableEventLoop.class)
abstract class BlockableEventLoopMixin {
	@WrapMethod(method = "managedBlock")
	private void tickengine$timeChunkWait(BooleanSupplier done, Operation<Void> original) {
		if (!TickProfiler.sampling || !((Object) this instanceof ChunkTaskExecutor)) {
			original.call(done);
			return;
		}
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		try {
			original.call(done);
		} finally {
			TickProfiler.endChunkWork(ChunkWork.WAIT, start, inner);
		}
	}
}
