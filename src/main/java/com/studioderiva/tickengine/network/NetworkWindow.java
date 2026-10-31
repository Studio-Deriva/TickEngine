package com.studioderiva.tickengine.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Accumulates the traffic of one network profiling session. Every method may be called from
 * several network threads at once; the maps are concurrent and the counters are adders.
 */
final class NetworkWindow {
	/** A chunk in a dimension, the place a packet is about. */
	record ChunkKey(ResourceKey<Level> dimension, int x, int z) {
	}

	/**
	 * Part of a packet charged to one source. Most packets have a single share; a packet that
	 * updates several blocks at once is split by how many of each block it carries.
	 */
	record Share(NetworkSource source, long weight, ChunkKey chunk, NetworkSnapshot.Spot spot) {
	}

	/** A packet whose raw size is recorded and whose compressed size is still to come. */
	record Pending(NetworkWindow window, Class<?> type, Share[] shares, long totalWeight) {
	}

	static final class SourceStats extends TrafficStats {
		final ConcurrentHashMap<Class<?>, LongAdder> wireByPacket = new ConcurrentHashMap<>();
	}

	static final class ChunkStats extends TrafficStats {
		final ConcurrentHashMap<NetworkSource, LongAdder> wireBySource = new ConcurrentHashMap<>();
		/** Where the latest packet about this chunk happened, so a click can go there instead of the chunk center. */
		volatile NetworkSnapshot.Spot spot;
	}

	final TrafficStats total = new TrafficStats();
	final ConcurrentHashMap<NetworkSource, SourceStats> sources = new ConcurrentHashMap<>();
	final ConcurrentHashMap<Class<?>, TrafficStats> packets = new ConcurrentHashMap<>();
	final ConcurrentHashMap<ChunkKey, ChunkStats> chunks = new ConcurrentHashMap<>();
	/** Packets compressed by a handler other than vanilla's, whose compressed size is unknown. */
	final LongAdder compressionUnmeasured = new LongAdder();
	final long startMillis = System.currentTimeMillis();

	/** Records a packet's raw size. Its compressed size follows through {@link #recordWire}. */
	Pending recordRaw(Class<?> type, Share[] shares, long totalWeight, long raw) {
		total.addPacket(raw);
		packets.computeIfAbsent(type, key -> new TrafficStats()).addPacket(raw);
		long given = 0;
		for (int i = 0; i < shares.length; i++) {
			Share share = shares[i];
			long part = part(raw, share.weight(), totalWeight, i == shares.length - 1, given);
			given += part;
			sources.computeIfAbsent(share.source(), key -> new SourceStats()).addPacket(part);
			if (share.chunk() != null) {
				ChunkStats chunk = chunks.computeIfAbsent(share.chunk(), key -> new ChunkStats());
				chunk.addPacket(part);
				chunk.spot = share.spot();
			}
		}
		return new Pending(this, type, shares, totalWeight);
	}

	void recordWire(Pending pending, long wire) {
		total.addWire(wire);
		packets.get(pending.type()).addWire(wire);
		Share[] shares = pending.shares();
		long given = 0;
		for (int i = 0; i < shares.length; i++) {
			Share share = shares[i];
			long part = part(wire, share.weight(), pending.totalWeight(), i == shares.length - 1, given);
			given += part;
			SourceStats source = sources.get(share.source());
			source.addWire(part);
			source.wireByPacket.computeIfAbsent(pending.type(), key -> new LongAdder()).add(part);
			if (share.chunk() != null) {
				ChunkStats chunk = chunks.get(share.chunk());
				chunk.addWire(part);
				chunk.wireBySource.computeIfAbsent(share.source(), key -> new LongAdder()).add(part);
			}
		}
	}

	/**
	 * A readable name for a packet class. 1.20.1 has no packet ids with names, so the class name is used:
	 * ClientboundMoveEntityPacket$Pos becomes ClientboundMoveEntityPacket.Pos. Mods' packet classes keep their
	 * own names; class names are not remapped at runtime on Forge 1.20.1.
	 */
	static String packetName(Class<?> type) {
		String name = type.getName();
		return name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
	}

	/** This share's part of {@code bytes}; the last share takes the rounding remainder, so parts add up. */
	static long part(long bytes, long weight, long totalWeight, boolean last, long alreadyGiven) {
		return last ? bytes - alreadyGiven : bytes * weight / totalWeight;
	}

	NetworkSnapshot snapshot(String label) {
		List<NetworkSnapshot.SourceEntry> sourceEntries = new ArrayList<>(sources.size());
		sources.forEach((source, stats) -> sourceEntries.add(new NetworkSnapshot.SourceEntry(source,
			stats.packets.sum(), stats.rawBytes.sum(), stats.wireBytes.sum(), topPackets(stats.wireByPacket))));

		List<NetworkSnapshot.PacketEntry> packetEntries = new ArrayList<>(packets.size());
		packets.forEach((type, stats) -> packetEntries.add(new NetworkSnapshot.PacketEntry(packetName(type),
			stats.packets.sum(), stats.rawBytes.sum(), stats.wireBytes.sum())));

		List<NetworkSnapshot.ChunkEntry> chunkEntries = new ArrayList<>(chunks.size());
		chunks.forEach((key, stats) -> {
			NetworkSource top = stats.wireBySource.entrySet().stream()
				.max(Comparator.comparingLong(entry -> entry.getValue().sum()))
				.map(Map.Entry::getKey)
				.orElse(null);
			chunkEntries.add(new NetworkSnapshot.ChunkEntry(key.dimension(), key.x(), key.z(), stats.packets.sum(), stats.wireBytes.sum(), top,
				stats.spot));
		});

		long durationMillis = System.currentTimeMillis() - startMillis;
		return new NetworkSnapshot(label, startMillis, durationMillis, total.packets.sum(), total.rawBytes.sum(),
			total.wireBytes.sum(), compressionUnmeasured.sum(), sourceEntries, packetEntries, chunkEntries);
	}

	/** The packet types that carried most of a source's bytes, largest first. */
	private static List<NetworkSnapshot.PacketShare> topPackets(Map<Class<?>, LongAdder> wireByPacket) {
		return wireByPacket.entrySet().stream()
			.map(entry -> new NetworkSnapshot.PacketShare(packetName(entry.getKey()), entry.getValue().sum()))
			.sorted(Comparator.comparingLong(NetworkSnapshot.PacketShare::wireBytes).reversed())
			.limit(3)
			.toList();
	}
}
