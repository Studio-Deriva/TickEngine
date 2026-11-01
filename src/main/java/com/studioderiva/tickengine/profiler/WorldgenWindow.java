package com.studioderiva.tickengine.profiler;

import com.studioderiva.tickengine.compat.MinecraftCompat;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Feature and structure placement times, written by the worldgen threads. Keys are the registry
 * objects (identity maps: PlacedFeature is a record, and hashing it would walk its whole contents).
 * One lock guards both maps; placing a feature takes far longer than taking an uncontended lock.
 */
final class WorldgenWindow {
	private final Reference2ObjectOpenHashMap<PlacedFeature, TypeStats> features = new Reference2ObjectOpenHashMap<>();
	private final Reference2ObjectOpenHashMap<Structure, TypeStats> structures = new Reference2ObjectOpenHashMap<>();

	synchronized void addFeature(PlacedFeature feature, long nanos, Level level, BlockPos pos) {
		TypeStats stats = features.get(feature);
		if (stats == null) {
			stats = new TypeStats();
			features.put(feature, stats);
		}
		stats.add(nanos, level == null ? null : level.dimension(), pos);
	}

	synchronized void addStructure(Structure structure, long nanos, Level level, BlockPos pos) {
		TypeStats stats = structures.get(structure);
		if (stats == null) {
			stats = new TypeStats();
			structures.put(structure, stats);
		}
		stats.add(nanos, level == null ? null : level.dimension(), pos);
	}

	/** Called on the server thread; ids come from the server's registries (worldgen registries are not built-in). */
	synchronized List<ChunkActivity.WorldgenEntry> features(RegistryAccess registries) {
		Registry<PlacedFeature> registry = registries == null ? null : MinecraftCompat.registry(registries, Registries.PLACED_FEATURE);
		return entries(features, feature -> {
			Identifier id = registry == null ? null : registry.getKey(feature);
			if (id != null) {
				return id;
			}
			// Placed inline by a biome or another feature: name it after its configured feature or feature type.
			return feature.feature().unwrapKey().map(key -> key.identifier())
				.orElseGet(() -> BuiltInRegistries.FEATURE.getKey(feature.feature().value().feature()));
		});
	}

	synchronized List<ChunkActivity.WorldgenEntry> structures(RegistryAccess registries) {
		Registry<Structure> registry = registries == null ? null : MinecraftCompat.registry(registries, Registries.STRUCTURE);
		return entries(structures, structure -> registry == null ? null : registry.getKey(structure));
	}

	/** Objects with the same id (inline features of one configured feature) are merged into one row. */
	private static <K> List<ChunkActivity.WorldgenEntry> entries(Reference2ObjectOpenHashMap<K, TypeStats> map,
		Function<K, Identifier> ids) {
		Map<String, ChunkActivity.WorldgenEntry> byId = new HashMap<>();
		map.forEach((key, stats) -> {
			Identifier id = ids.apply(key);
			String name = id != null ? id.toString() : "unregistered";
			ChunkActivity.WorldgenEntry entry = new ChunkActivity.WorldgenEntry(name, id != null ? id.getNamespace() : "unknown",
				stats.totalNanos, stats.calls, stats.worstNanos, stats.worstPos, stats.worstDimension);
			byId.merge(name, entry, WorldgenWindow::merge);
		});
		List<ChunkActivity.WorldgenEntry> entries = new ArrayList<>(byId.values());
		entries.sort(Comparator.comparingLong(ChunkActivity.WorldgenEntry::totalNanos).reversed());
		return entries;
	}

	private static ChunkActivity.WorldgenEntry merge(ChunkActivity.WorldgenEntry a, ChunkActivity.WorldgenEntry b) {
		ChunkActivity.WorldgenEntry worst = a.worstNanos() >= b.worstNanos() ? a : b;
		return new ChunkActivity.WorldgenEntry(a.id(), a.namespace(), a.totalNanos() + b.totalNanos(), a.calls() + b.calls(),
			worst.worstNanos(), worst.worstPos(), worst.worstDimension());
	}
}
