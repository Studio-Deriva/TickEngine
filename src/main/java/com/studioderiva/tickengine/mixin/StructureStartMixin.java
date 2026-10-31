package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;

/** Times placing the part of a structure that falls in one chunk, on the worldgen threads. */
@Mixin(StructureStart.class)
abstract class StructureStartMixin {
	@WrapMethod(method = "placeInChunk")
	private void tickengine$timeStructure(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
		RandomSource random, BoundingBox box, ChunkPos chunk, Operation<Void> original) {
		if (!TickProfiler.collecting) {
			original.call(level, structures, generator, random, box, chunk);
			return;
		}
		long start = System.nanoTime();
		try {
			original.call(level, structures, generator, random, box, chunk);
		} finally {
			TickProfiler.recordStructure(((StructureStart) (Object) this).getStructure(), System.nanoTime() - start,
				level.getLevel(), chunk.getMiddleBlockPosition(level.getSeaLevel()));
		}
	}
}
