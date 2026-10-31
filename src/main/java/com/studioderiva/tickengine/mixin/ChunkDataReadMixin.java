package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 {
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
*///?}

/**
 * Turning saved chunk data into a chunk: in vanilla this runs on the server thread, after the file
 * was read and parsed on other threads. Counts every chunk loaded from disk.
 */
//? if >=1.21.2 {
@Mixin(SerializableChunkData.class)
//?} else {
/*@Mixin(ChunkSerializer.class)
*///?}
abstract class ChunkDataReadMixin {
	//? if >=1.21.2 {
	@WrapMethod(method = "read")
	private ProtoChunk tickengine$timeRead(ServerLevel level, PoiManager poiManager, RegionStorageInfo storage, ChunkPos pos,
		Operation<ProtoChunk> original) {
	//?} else {
	/*@WrapMethod(method = "read")
	private static ProtoChunk tickengine$timeRead(ServerLevel level, PoiManager poiManager, RegionStorageInfo storage,
		ChunkPos pos, CompoundTag tag, Operation<ProtoChunk> original) {
	*///?}
		if (!TickProfiler.collecting || !TickProfiler.isServerThread()) {
			//? if >=1.21.2 {
			return original.call(level, poiManager, storage, pos);
			//?} else {
			/*return original.call(level, poiManager, storage, pos, tag);
			*///?}
		}
		TickProfiler.chunkLoaded();
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		try {
			//? if >=1.21.2 {
			return original.call(level, poiManager, storage, pos);
			//?} else {
			/*return original.call(level, poiManager, storage, pos, tag);
			*///?}
		} finally {
			TickProfiler.endChunkWork(ChunkWork.LOAD, start, inner);
		}
	}
}
