package com.studioderiva.tickengine.profiler;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
	/** Types named in each chunk's row, the ones that took most of its time. */
	static final int CHUNK_TOP_TYPES = 3;

	/** One identity map per category, indexed by ordinal. Random ticks hold both blocks and fluids. */
	@SuppressWarnings("unchecked")
	private final Reference2ObjectOpenHashMap<Object, TypeStats>[] stats = new Reference2ObjectOpenHashMap[CATEGORIES.length];
	/** Time by chunk, per dimension, keyed by {@link #chunkKey}. Dimension keys are interned. */
	private final Reference2ObjectOpenHashMap<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkTime>> chunkTimes =
		new Reference2ObjectOpenHashMap<>();
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

	/** {@code dimension} and {@code pos} may be null (chunk work). */
	void add(ProfileSnapshot.Category category, Object key, long nanos, ResourceKey<Level> dimension, BlockPos pos) {
		Reference2ObjectOpenHashMap<Object, TypeStats> map = stats[category.ordinal()];
		TypeStats typeStats = map.get(key);
		if (typeStats == null) {
			typeStats = new TypeStats();
			map.put(key, typeStats);
		}
		typeStats.add(nanos, dimension, pos);
		if (dimension != null && pos != null) {
			addToChunk(category, key, nanos, dimension, pos);
		}
	}

	private void addToChunk(ProfileSnapshot.Category category, Object key, long nanos, ResourceKey<Level> dimension,
		BlockPos pos) {
		Long2ObjectOpenHashMap<ChunkTime> chunks = chunkTimes.get(dimension);
		if (chunks == null) {
			chunks = new Long2ObjectOpenHashMap<>();
			chunkTimes.put(dimension, chunks);
		}
		long chunkKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
		ChunkTime chunk = chunks.get(chunkKey);
		if (chunk == null) {
			chunk = new ChunkTime();
			chunks.put(chunkKey, chunk);
		}
		chunk.add(category, key, nanos, pos);
	}

	/** Packs chunk coordinates like ChunkPos.asLong, without loading ChunkPos (whose statics need a bootstrapped game). */
	private static long chunkKey(int x, int z) {
		return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
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
		return new ProfileSnapshot(label, startMillis, durationMillis, sampledTicks, sampledTickNanos, entries, chunks,
			chunkEntries());
	}

	private List<ProfileSnapshot.ChunkEntry> chunkEntries() {
		List<ProfileSnapshot.ChunkEntry> entries = new ArrayList<>();
		chunkTimes.forEach((dimension, chunks) -> chunks.long2ObjectEntrySet().forEach(entry -> {
			ChunkTime chunk = entry.getValue();
			List<ProfileSnapshot.TypeShare> types = new ArrayList<>();
			for (ProfileSnapshot.Category category : CATEGORIES) {
				Reference2LongOpenHashMap<Object> byType = chunk.typeNanos[category.ordinal()];
				if (byType != null) {
					byType.reference2LongEntrySet().forEach(type -> types.add(
						new ProfileSnapshot.TypeShare(category, name(category, type.getKey()), type.getLongValue())));
				}
			}
			types.sort(Comparator.comparingLong(ProfileSnapshot.TypeShare::nanos).reversed());
			long key = entry.getLongKey();
			entries.add(new ProfileSnapshot.ChunkEntry(dimension, (int) key, (int) (key >>> 32), chunk.totalNanos,
				chunk.categoryNanos.clone(), List.copyOf(types.subList(0, Math.min(CHUNK_TOP_TYPES, types.size()))),
				chunk.worstPos, chunk.worstCategory));
		}));
		return entries;
	}

	/** Mutable time spent in one chunk. Server thread only. */
	private static final class ChunkTime {
		long totalNanos;
		final long[] categoryNanos = new long[CATEGORIES.length];
		/** Per category, by type; created when the category first shows up in this chunk. */
		@SuppressWarnings("unchecked")
		final Reference2LongOpenHashMap<Object>[] typeNanos = new Reference2LongOpenHashMap[CATEGORIES.length];
		long worstNanos;
		BlockPos worstPos;
		ProfileSnapshot.Category worstCategory;

		void add(ProfileSnapshot.Category category, Object key, long nanos, BlockPos pos) {
			totalNanos += nanos;
			categoryNanos[category.ordinal()] += nanos;
			Reference2LongOpenHashMap<Object> byType = typeNanos[category.ordinal()];
			if (byType == null) {
				byType = new Reference2LongOpenHashMap<>();
				typeNanos[category.ordinal()] = byType;
			}
			byType.addTo(key, nanos);
			if (nanos > worstNanos) {
				worstNanos = nanos;
				// BlockPos.MutableBlockPos instances can be reused by the caller.
				worstPos = pos.immutable();
				worstCategory = category;
			}
		}
	}

	private static ProfileSnapshot.Entry entry(ProfileSnapshot.Category category, Object key, TypeStats stats) {
		if (key instanceof ChunkWork work) {
			return new ProfileSnapshot.Entry(category, work.displayName, "minecraft", stats.totalNanos, stats.calls,
				stats.worstNanos, null, null);
		}
		Identifier id = id(key);
		return new ProfileSnapshot.Entry(category, name(category, key), id != null ? id.getNamespace() : "unknown",
			stats.totalNanos, stats.calls, stats.worstNanos, stats.worstPos, stats.worstDimension);
	}

	/** Registry id with a suffix where one id names two rows. Unregistered types (rare, broken mods) still show up. */
	private static String name(ProfileSnapshot.Category category, Object key) {
		Identifier id = id(key);
		if (id == null) {
			return "unregistered";
		}
		// Lava is both a block and a fluid; tell the two random tick rows apart.
		return key instanceof Fluid && category == ProfileSnapshot.Category.RANDOM_TICK ? id + " (fluid)" : id.toString();
	}

	private static Identifier id(Object key) {
		Identifier id;
		if (key instanceof BlockEntityType<?> type) {
			id = BlockEntityType.getKey(type);
		} else if (key instanceof EntityType<?> type) {
			id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		} else if (key instanceof Block block) {
			id = BuiltInRegistries.BLOCK.getKey(block);
		} else if (key instanceof Fluid fluid) {
			id = BuiltInRegistries.FLUID.getKey(fluid);
		} else {
			id = null;
		}
		return id;
	}
}
