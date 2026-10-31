package com.studioderiva.tickengine.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.fml.ModList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable result of a profiling window or session, sorted by total time (highest first). */
public final class ProfileSnapshot {
	public enum Category {
		BLOCK_ENTITY("Block entity"),
		ENTITY("Entity");

		public final String displayName;

		Category(String displayName) {
			this.displayName = displayName;
		}
	}

	public record Entry(Category category, String id, String namespace, long totalNanos, long calls,
		long worstNanos, BlockPos worstPos, ResourceKey<Level> worstDimension) {
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

	ProfileSnapshot(String label, long startMillis, long durationMillis, long sampledTicks, long sampledTickNanos,
		List<Entry> entries) {
		this.label = label;
		this.startMillis = startMillis;
		this.durationMillis = durationMillis;
		this.sampledTicks = sampledTicks;
		this.sampledTickNanos = sampledTickNanos;
		List<Entry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparingLong(Entry::totalNanos).reversed());
		this.entries = List.copyOf(sorted);
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

	public List<ModEntry> mods() {
		Map<String, long[]> totals = new LinkedHashMap<>();
		for (Entry entry : entries) {
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
