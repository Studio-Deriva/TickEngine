package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Turning saved chunk data into a chunk: in vanilla this runs on the server thread, after the file
 * was read on another thread. Counts every chunk loaded from disk.
 */
@Mixin(ChunkSerializer.class)
abstract class ChunkDataReadMixin {
	@WrapMethod(method = "read")
	private static ProtoChunk tickengine$timeRead(ServerLevel level, PoiManager poiManager, ChunkPos pos, CompoundTag tag,
		Operation<ProtoChunk> original) {
		if (!TickProfiler.collecting || !TickProfiler.isServerThread()) {
			return original.call(level, poiManager, pos, tag);
		}
		TickProfiler.chunkLoaded();
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		try {
			return original.call(level, poiManager, pos, tag);
		} finally {
			TickProfiler.endChunkWork(ChunkWork.LOAD, start, inner);
		}
	}
}
