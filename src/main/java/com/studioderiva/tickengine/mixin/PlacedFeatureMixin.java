package com.studioderiva.tickengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.studioderiva.tickengine.profiler.TickProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Times each feature a biome places in a chunk (trees, ores, flowers, lakes), on the worldgen
 * threads. Only the top-level call from biome decoration goes through placeWithBiomeCheck, so
 * features placing other features are not counted twice.
 */
@Mixin(PlacedFeature.class)
abstract class PlacedFeatureMixin {
	@WrapMethod(method = "placeWithBiomeCheck")
	private boolean tickengine$timeFeature(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
		Operation<Boolean> original) {
		if (!TickProfiler.collecting) {
			return original.call(level, generator, random, origin);
		}
		long start = System.nanoTime();
		try {
			return original.call(level, generator, random, origin);
		} finally {
			TickProfiler.recordFeature((PlacedFeature) (Object) this, System.nanoTime() - start, level.getLevel(), origin);
		}
	}
}
