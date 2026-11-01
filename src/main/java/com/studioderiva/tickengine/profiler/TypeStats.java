package com.studioderiva.tickengine.profiler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Mutable timing totals for one type (block entity, entity, block, fluid or chunk work). Server thread only. */
final class TypeStats {
	long totalNanos;
	long calls;
	long worstNanos;
	BlockPos worstPos;
	ResourceKey<Level> worstDimension;

	/** {@code dimension} and {@code pos} may be null for work that has no single place, like chunk saving. */
	void add(long nanos, ResourceKey<Level> dimension, BlockPos pos) {
		totalNanos += nanos;
		calls++;
		if (nanos > worstNanos) {
			worstNanos = nanos;
			// BlockPos.MutableBlockPos instances can be reused by the caller.
			worstPos = pos == null ? null : pos.immutable();
			worstDimension = dimension;
		}
	}
}
