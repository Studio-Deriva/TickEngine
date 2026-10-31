package com.studioderiva.tickengine.network;

import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable result of a network profiling session. Every list is sorted by bytes on the wire, highest first. */
public final class NetworkSnapshot {
	public record PacketShare(String packet, long wireBytes) {
	}

	/** A block position. */
	public record Spot(int x, int y, int z) {
	}

	/** Traffic about one source, with the packet types that carried most of it. */
	public record SourceEntry(NetworkSource source, long packets, long rawBytes, long wireBytes,
		List<PacketShare> topPackets) {
	}

	public record PacketEntry(String id, long packets, long rawBytes, long wireBytes) {
	}

	/** Traffic about one chunk: packets for its blocks, block entities, entities, sounds and particles. */
	public record ChunkEntry(ResourceKey<Level> dimension, int x, int z, long packets, long wireBytes,
		NetworkSource topSource, Spot spot) {
	}

	/** Traffic of every source from one mod, grouped by registry namespace. */
	public record ModEntry(String namespace, String displayName, long packets, long rawBytes, long wireBytes,
		int sources) {
	}

	public final String label;
	public final long startMillis;
	public final long durationMillis;
	public final long packets;
	public final long rawBytes;
	public final long wireBytes;
	/**
	 * Packets compressed by another mod's handler; for them the sent size is the
	 * raw size.
	 */
	public final long compressionUnmeasured;
	public final List<SourceEntry> sources;
	public final List<PacketEntry> packetTypes;
	public final List<ChunkEntry> chunks;

	NetworkSnapshot(String label, long startMillis, long durationMillis, long packets, long rawBytes, long wireBytes,
		long compressionUnmeasured, List<SourceEntry> sources, List<PacketEntry> packetTypes, List<ChunkEntry> chunks) {
		this.label = label;
		this.startMillis = startMillis;
		this.durationMillis = durationMillis;
		this.packets = packets;
		this.rawBytes = rawBytes;
		this.wireBytes = wireBytes;
		this.compressionUnmeasured = compressionUnmeasured;
		this.sources = sorted(sources, SourceEntry::wireBytes);
		this.packetTypes = sorted(packetTypes, PacketEntry::wireBytes);
		this.chunks = sorted(chunks, ChunkEntry::wireBytes);
	}

	private static <T> List<T> sorted(List<T> list, ToLongFunction<T> key) {
		List<T> copy = new ArrayList<>(list);
		copy.sort(Comparator.comparingLong(key).reversed());
		return List.copyOf(copy);
	}

	public boolean isEmpty() {
		return packets == 0;
	}

	private double seconds() {
		return Math.max(1, durationMillis) / 1000.0;
	}

	public double perSecond(long value) {
		return value / seconds();
	}

	/** Share of all bytes on the wire, from 0 to 100. */
	public double percent(long wire) {
		return wireBytes == 0 ? 0 : wire * 100.0 / wireBytes;
	}

	/** Bytes saved by compression, from 0 to 100. */
	public double compressionSaving() {
		return rawBytes == 0 ? 0 : 100.0 - wireBytes * 100.0 / rawBytes;
	}

	public List<SourceEntry> sources(NetworkSource.Kind kind) {
		return sources.stream().filter(entry -> entry.source().kind() == kind).toList();
	}

	public List<ModEntry> mods() {
		Map<String, long[]> totals = new LinkedHashMap<>();
		for (SourceEntry entry : sources) {
			long[] total = totals.computeIfAbsent(entry.source().namespace(), key -> new long[4]);
			total[0] += entry.packets();
			total[1] += entry.rawBytes();
			total[2] += entry.wireBytes();
			total[3]++;
		}
		List<ModEntry> mods = new ArrayList<>(totals.size());
		totals.forEach((namespace, total) -> mods.add(new ModEntry(namespace, ProfileSnapshot.modName(namespace),
			total[0], total[1], total[2], (int) total[3])));
		mods.sort(Comparator.comparingLong(ModEntry::wireBytes).reversed());
		return mods;
	}

	/** Bytes as B, KB or MB (1 KB = 1024 bytes). */
	public static String formatBytes(double bytes) {
		if (bytes >= 1024 * 1024) {
			return format(bytes / (1024 * 1024)) + " MB";
		}
		if (bytes >= 1024) {
			return format(bytes / 1024) + " KB";
		}
		return Math.round(bytes) + " B";
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f", value);
	}
}
