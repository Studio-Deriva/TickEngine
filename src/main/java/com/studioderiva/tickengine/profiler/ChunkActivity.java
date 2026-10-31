package com.studioderiva.tickengine.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Chunk loading and generation over a profiling window or session. Unlike the tick timings these
 * are counted over the whole window, not only on sampled ticks: chunks load and generate between
 * ticks and on worker threads too.
 *
 * @param loaded chunks read from disk
 * @param created chunks with nothing saved, which start generating from scratch
 * @param generated chunks that finished generating and became full chunks
 * @param betweenTickNanos server thread time on chunk tasks between ticks, when the server had time to spare
 * @param generatedNear generated chunks by the nearest player when they finished, most first
 * @param features feature placement (trees, ores, flowers) on worldgen threads, slowest first
 * @param structures structure placement (villages, strongholds) on worldgen threads, slowest first
 */
public record ChunkActivity(long loaded, long created, long generated, long betweenTickNanos,
	List<PlayerChunks> generatedNear, List<WorldgenEntry> features, List<WorldgenEntry> structures) {

	public static final ChunkActivity EMPTY = new ChunkActivity(0, 0, 0, 0, List.of(), List.of(), List.of());

	/** Generated chunks near one player. "nobody" when no player was in the dimension. */
	public record PlayerChunks(String player, long chunks) {
	}

	/** One placed feature or structure: total time on worldgen threads and its slowest placement. */
	public record WorldgenEntry(String id, String namespace, long totalNanos, long calls, long worstNanos,
		BlockPos worstPos, ResourceKey<Level> worstDimension) {
	}

	public long featureNanos() {
		return features.stream().mapToLong(WorldgenEntry::totalNanos).sum();
	}

	public long structureNanos() {
		return structures.stream().mapToLong(WorldgenEntry::totalNanos).sum();
	}

	public boolean isEmpty() {
		return loaded == 0 && created == 0 && generated == 0 && betweenTickNanos == 0
			&& features.isEmpty() && structures.isEmpty();
	}

	/** Features and structures grouped by mod, slowest first. */
	public List<ProfileSnapshot.ModEntry> worldgenMods() {
		Map<String, long[]> totals = new LinkedHashMap<>();
		for (List<WorldgenEntry> list : List.of(features, structures)) {
			for (WorldgenEntry entry : list) {
				long[] total = totals.computeIfAbsent(entry.namespace(), key -> new long[3]);
				total[0] += entry.totalNanos();
				total[1] += entry.calls();
				total[2]++;
			}
		}
		List<ProfileSnapshot.ModEntry> mods = new ArrayList<>(totals.size());
		totals.forEach((namespace, total) -> mods.add(new ProfileSnapshot.ModEntry(namespace,
			ProfileSnapshot.modName(namespace), total[0], total[1], (int) total[2])));
		mods.sort(Comparator.comparingLong(ProfileSnapshot.ModEntry::totalNanos).reversed());
		return mods;
	}
}
