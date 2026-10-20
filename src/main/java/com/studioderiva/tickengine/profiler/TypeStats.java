package com.studioderiva.tickengine.profiler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Mutable timing totals for one block entity type or entity type. Server thread only. */
final class TypeStats {
	long totalNanos;
	long calls;
	long worstNanos;
	BlockPos worstPos;
	ResourceKey<Level> worstDimension;

	void add(long nanos, Level level, BlockPos pos) {
		totalNanos += nanos;
		calls++;
		if (nanos > worstNanos) {
			worstNanos = nanos;
			// BlockPos.MutableBlockPos instances can be reused by the caller.
			worstPos = pos.immutable();
			worstDimension = level.dimension();
		}
	}
}
