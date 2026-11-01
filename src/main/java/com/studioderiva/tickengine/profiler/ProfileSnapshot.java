package com.studioderiva.tickengine.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraftforge.fml.ModList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable result of a profiling window or session, sorted by total time (highest first). */
public final class ProfileSnapshot {
	public enum Category {
		BLOCK_ENTITY("Block entity"),
		ENTITY("Entity"),
		/** Scheduled block ticks: repeaters, comparators, observers, falling blocks, most redstone. */
		BLOCK_TICK("Scheduled block tick"),
		/** Scheduled fluid ticks: water and lava flowing. */
		FLUID_TICK("Fluid tick"),
		/** Random ticks: crops, saplings, leaves, ice, fire, lava setting fire. */
		RANDOM_TICK("Random tick"),
		/** Block events: pistons, note blocks, chests and bells opening. */
		BLOCK_EVENT("Block event"),
		/** Chunk work on the server thread, by kind ({@link ChunkWork}). Not a mod: left out of {@link #mods()}. */
		CHUNK("Chunk work");

		public final String displayName;

		Category(String displayName) {
			this.displayName = displayName;
		}
	}

	/** The block categories, shown together as one ranking. */
	public static final Set<Category> BLOCK_CATEGORIES =
		EnumSet.of(Category.BLOCK_TICK, Category.FLUID_TICK, Category.RANDOM_TICK, Category.BLOCK_EVENT);

	public record Entry(Category category, String id, String namespace, long totalNanos, long calls,
		long worstNanos, BlockPos worstPos, ResourceKey<Level> worstDimension) {
	}

	/**
	 * Tick time spent in one chunk: block entities, entities and blocks there, counted where they
	 * were when they ticked. Chunk work (loading, saving) has no single chunk and is left out.
	 *
	 * @param categoryNanos time by {@link Category}, indexed by ordinal
	 * @param topTypes the types that took most of the time here, highest first
	 * @param worstPos where the slowest single call in this chunk happened, to teleport to
	 * @param worstCategory what ran at {@code worstPos}
	 */
	public record ChunkEntry(ResourceKey<Level> dimension, int x, int z, long totalNanos, long[] categoryNanos,
		List<TypeShare> topTypes, BlockPos worstPos, Category worstCategory) {

		public long nanos(Category category) {
			return categoryNanos[category.ordinal()];
		}

		/** Scheduled block and fluid ticks, random ticks and block events together. */
		public long blockNanos() {
			long nanos = 0;
			for (Category category : BLOCK_CATEGORIES) {
				nanos += nanos(category);
			}
			return nanos;
		}
	}

	/** One type's time inside a chunk. */
	public record TypeShare(Category category, String id, long nanos) {
	}

	/** Time of every type from one mod, grouped by registry namespace. */
	public record ModEntry(String namespace, String displayName, long totalNanos, long calls, int types) {
	}

	public final String label;
	public final long startMillis;
	public final long durationMillis;
	public final long sampledTicks;
	public final long sampledTickNanos;
	public final List<Entry> entries;
	public final ChunkActivity chunks;
	/** Chunks by tick time spent in them, highest first. */
	public final List<ChunkEntry> chunkTimes;

	ProfileSnapshot(String label, long startMillis, long durationMillis, long sampledTicks, long sampledTickNanos,
		List<Entry> entries, ChunkActivity chunks, List<ChunkEntry> chunkTimes) {
		this.label = label;
		this.startMillis = startMillis;
		this.durationMillis = durationMillis;
		this.sampledTicks = sampledTicks;
		this.sampledTickNanos = sampledTickNanos;
		List<Entry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparingLong(Entry::totalNanos).reversed());
		this.entries = List.copyOf(sorted);
		this.chunks = chunks;
		List<ChunkEntry> sortedChunks = new ArrayList<>(chunkTimes);
		sortedChunks.sort(Comparator.comparingLong(ChunkEntry::totalNanos).reversed());
		this.chunkTimes = List.copyOf(sortedChunks);
	}

	public boolean isEmpty() {
		return sampledTicks == 0;
	}

	/** Average milliseconds per tick spent in this entry. */
	public double msPerTick(long totalNanos) {
		return sampledTicks == 0 ? 0 : totalNanos / (double) sampledTicks / 1_000_000.0;
	}

	/** Share of the measured tick time, from 0 to 100. */
	public double percentOfTick(long totalNanos) {
		return sampledTickNanos == 0 ? 0 : totalNanos * 100.0 / sampledTickNanos;
	}

	/** Average length of a sampled tick in milliseconds (MSPT while sampling). */
	public double averageTickMs() {
		return sampledTicks == 0 ? 0 : sampledTickNanos / (double) sampledTicks / 1_000_000.0;
	}

	public List<Entry> entries(Category category) {
		return entries.stream().filter(entry -> entry.category() == category).toList();
	}

	/** Scheduled block and fluid ticks, random ticks and block events in one list, highest first. */
	public List<Entry> blockEntries() {
		return entries.stream().filter(entry -> BLOCK_CATEGORIES.contains(entry.category())).toList();
	}

	/** Per wall-clock second of the window, for counts that are not tied to sampled ticks. */
	public double perSecond(double value) {
		return durationMillis <= 0 ? 0 : value * 1000.0 / durationMillis;
	}

	public List<ModEntry> mods() {
		Map<String, long[]> totals = new LinkedHashMap<>();
		for (Entry entry : entries) {
			if (entry.category() == Category.CHUNK) {
				continue;
			}
			long[] total = totals.computeIfAbsent(entry.namespace(), key -> new long[3]);
			total[0] += entry.totalNanos();
			total[1] += entry.calls();
			total[2]++;
		}
		List<ModEntry> mods = new ArrayList<>(totals.size());
		totals.forEach((namespace, total) ->
			mods.add(new ModEntry(namespace, modName(namespace), total[0], total[1], (int) total[2])));
		mods.sort(Comparator.comparingLong(ModEntry::totalNanos).reversed());
		return mods;
	}

	/**
	 * Registry namespaces usually match the mod id; fall back to the namespace when they do not, or when
	 * FML has not loaded mods (plain unit tests).
	 */
	public static String modName(String namespace) {
		ModList mods = ModList.get();
		if (mods == null) {
			return namespace;
		}
		return mods.getModContainerById(namespace)
			.map(container -> container.getModInfo().getDisplayName())
			.orElse(namespace);
	}
}
