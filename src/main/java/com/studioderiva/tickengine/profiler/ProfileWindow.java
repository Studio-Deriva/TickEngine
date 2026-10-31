package com.studioderiva.tickengine.profiler;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;

/**
 * Accumulates timings over a period of sampled ticks. Keys are the registry objects
 * themselves (identity map), so recording never allocates strings.
 *
 * <p>Everything here is server thread only, except {@link #worldgen}, which worldgen threads write.
 */
final class ProfileWindow {
	private static final ProfileSnapshot.Category[] CATEGORIES = ProfileSnapshot.Category.values();

	/** One identity map per category, indexed by ordinal. Random ticks hold both blocks and fluids. */
	@SuppressWarnings("unchecked")
	private final Reference2ObjectOpenHashMap<Object, TypeStats>[] stats = new Reference2ObjectOpenHashMap[CATEGORIES.length];
	private final long startMillis = System.currentTimeMillis();
	private long sampledTicks;
	private long sampledTickNanos;

	private long chunksLoaded;
	private long chunksCreated;
	private long chunksGenerated;
	private long betweenTickChunkNanos;
	private final Object2LongOpenHashMap<String> generatedNear = new Object2LongOpenHashMap<>();
	final WorldgenWindow worldgen = new WorldgenWindow();

	ProfileWindow() {
		for (int i = 0; i < stats.length; i++) {
			stats[i] = new Reference2ObjectOpenHashMap<>();
		}
	}

	/** {@code level} and {@code pos} may be null (chunk work). */
	void add(ProfileSnapshot.Category category, Object key, long nanos, Level level, BlockPos pos) {
		Reference2ObjectOpenHashMap<Object, TypeStats> map = stats[category.ordinal()];
		TypeStats typeStats = map.get(key);
		if (typeStats == null) {
			typeStats = new TypeStats();
			map.put(key, typeStats);
		}
		typeStats.add(nanos, level, pos);
	}

	void addTick(long nanos) {
		sampledTicks++;
		sampledTickNanos += nanos;
	}

	void chunkLoaded() {
		chunksLoaded++;
	}

	void chunkCreated() {
		chunksCreated++;
	}

	void chunkGenerated(String nearestPlayer) {
		chunksGenerated++;
		generatedNear.addTo(nearestPlayer, 1);
	}

	void addBetweenTicks(long nanos) {
		betweenTickChunkNanos += nanos;
	}

	long sampledTicks() {
		return sampledTicks;
	}

	long startMillis() {
		return startMillis;
	}

	ProfileSnapshot snapshot(String label, RegistryAccess registries) {
		List<ProfileSnapshot.Entry> entries = new ArrayList<>();
		for (ProfileSnapshot.Category category : CATEGORIES) {
			stats[category.ordinal()].forEach((key, typeStats) -> entries.add(entry(category, key, typeStats)));
		}
		List<ChunkActivity.PlayerChunks> near = new ArrayList<>(generatedNear.size());
		generatedNear.object2LongEntrySet().forEach(entry ->
			near.add(new ChunkActivity.PlayerChunks(entry.getKey(), entry.getLongValue())));
		near.sort(Comparator.comparingLong(ChunkActivity.PlayerChunks::chunks).reversed());
		ChunkActivity chunks = new ChunkActivity(chunksLoaded, chunksCreated, chunksGenerated, betweenTickChunkNanos,
			near, worldgen.features(registries), worldgen.structures(registries));
		long durationMillis = System.currentTimeMillis() - startMillis;
		return new ProfileSnapshot(label, startMillis, durationMillis, sampledTicks, sampledTickNanos, entries, chunks);
	}

	private static ProfileSnapshot.Entry entry(ProfileSnapshot.Category category, Object key, TypeStats stats) {
		String suffix = "";
		ResourceLocation id;
		if (key instanceof ChunkWork work) {
			return new ProfileSnapshot.Entry(category, work.displayName, "minecraft", stats.totalNanos, stats.calls,
				stats.worstNanos, null, null);
		} else if (key instanceof BlockEntityType<?> type) {
			id = BlockEntityType.getKey(type);
		} else if (key instanceof EntityType<?> type) {
			id = ForgeRegistries.ENTITY_TYPES.getKey(type);
		} else if (key instanceof Block block) {
			id = ForgeRegistries.BLOCKS.getKey(block);
		} else if (key instanceof Fluid fluid) {
			id = ForgeRegistries.FLUIDS.getKey(fluid);
			// Lava is both a block and a fluid; tell the two random tick rows apart.
			suffix = category == ProfileSnapshot.Category.RANDOM_TICK ? " (fluid)" : "";
		} else {
			id = null;
		}
		// Unregistered types (rare, broken mods) still show up instead of crashing the report.
		String name = id != null ? id + suffix : "unregistered";
		String namespace = id != null ? id.getNamespace() : "unknown";
		return new ProfileSnapshot.Entry(category, name, namespace, stats.totalNanos, stats.calls,
			stats.worstNanos, stats.worstPos, stats.worstDimension);
	}
}
