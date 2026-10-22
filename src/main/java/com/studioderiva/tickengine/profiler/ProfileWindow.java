package com.studioderiva.tickengine.profiler;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Accumulates timings over a period of sampled ticks. Keys are the registry objects
 * themselves (identity map), so recording never allocates strings.
 */
final class ProfileWindow {
	private final Reference2ObjectOpenHashMap<BlockEntityType<?>, TypeStats> blockEntities = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectOpenHashMap<EntityType<?>, TypeStats> entities = new Reference2ObjectOpenHashMap<>();
	private final long startMillis = System.currentTimeMillis();
	private long sampledTicks;
	private long sampledTickNanos;

	void addBlockEntity(BlockEntityType<?> type, long nanos, Level level, BlockPos pos) {
		TypeStats stats = blockEntities.get(type);
		if (stats == null) {
			stats = new TypeStats();
			blockEntities.put(type, stats);
		}
		stats.add(nanos, level, pos);
	}

	void addEntity(EntityType<?> type, long nanos, Level level, BlockPos pos) {
		TypeStats stats = entities.get(type);
		if (stats == null) {
			stats = new TypeStats();
			entities.put(type, stats);
		}
		stats.add(nanos, level, pos);
	}

	void addTick(long nanos) {
		sampledTicks++;
		sampledTickNanos += nanos;
	}

	long sampledTicks() {
		return sampledTicks;
	}

	long startMillis() {
		return startMillis;
	}

	ProfileSnapshot snapshot(String label) {
		List<ProfileSnapshot.Entry> entries = new ArrayList<>(blockEntities.size() + entities.size());
		blockEntities.forEach((type, stats) ->
			entries.add(entry(ProfileSnapshot.Category.BLOCK_ENTITY, BlockEntityType.getKey(type), stats)));
		entities.forEach((type, stats) ->
			entries.add(entry(ProfileSnapshot.Category.ENTITY, ForgeRegistries.ENTITY_TYPES.getKey(type), stats)));
		long durationMillis = System.currentTimeMillis() - startMillis;
		return new ProfileSnapshot(label, startMillis, durationMillis, sampledTicks, sampledTickNanos, entries);
	}

	private static ProfileSnapshot.Entry entry(ProfileSnapshot.Category category, ResourceLocation id, TypeStats stats) {
		// Unregistered types (rare, broken mods) still show up instead of crashing the report.
		String name = id != null ? id.toString() : "unregistered";
		String namespace = id != null ? id.getNamespace() : "unknown";
		return new ProfileSnapshot.Entry(category, name, namespace, stats.totalNanos, stats.calls,
			stats.worstNanos, stats.worstPos, stats.worstDimension);
	}
}
