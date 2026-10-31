package com.studioderiva.tickengine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Counting and splitting of packet sizes, without a server or real packets. */
class NetworkWindowTest {
	// Stand-ins for packet classes: 1.20.1 names packet types after their class.
	private static final class SectionBlocksUpdate {
	}

	private static final class SetEntityData {
	}

	private static final Class<?> BLOCK_UPDATES = SectionBlocksUpdate.class;
	private static final Class<?> ENTITY_DATA = SetEntityData.class;
	private static final NetworkSource STONE = new NetworkSource(NetworkSource.Kind.BLOCK, "minecraft:stone", "minecraft");
	private static final NetworkSource PIPE = new NetworkSource(NetworkSource.Kind.BLOCK, "pipes:pipe", "pipes");
	private static final NetworkSource ZOMBIE = new NetworkSource(NetworkSource.Kind.ENTITY, "minecraft:zombie", "minecraft");
	private static final NetworkWindow.ChunkKey CHUNK = new NetworkWindow.ChunkKey(
		// No dimension: creating a ResourceKey needs Minecraft's registries, which plain JUnit does not start.
		null, 3, -2);

	private static final NetworkSnapshot.Spot SPOT = new NetworkSnapshot.Spot(55, 70, -20);

	private static NetworkWindow.Share[] one(NetworkSource source) {
		return new NetworkWindow.Share[] { new NetworkWindow.Share(source, 1, CHUNK, SPOT) };
	}

	@Test
	void partsAlwaysAddUpToTheWhole() {
		long bytes = 1001;
		long[] weights = { 1, 1, 1 };
		long given = 0;
		for (int i = 0; i < weights.length; i++) {
			given += NetworkWindow.part(bytes, weights[i], 3, i == weights.length - 1, given);
		}
		assertEquals(bytes, given);
	}

	@Test
	void splitPacketIsChargedByWeight() {
		NetworkWindow window = new NetworkWindow();
		NetworkWindow.Share[] shares = {
			new NetworkWindow.Share(STONE, 3, CHUNK, SPOT),
			new NetworkWindow.Share(PIPE, 1, CHUNK, SPOT)
		};
		NetworkWindow.Pending pending = window.recordRaw(BLOCK_UPDATES, shares, 4, 400);
		window.recordWire(pending, 100);

		NetworkSnapshot snapshot = window.snapshot("test");
		assertEquals(1, snapshot.packets);
		assertEquals(400, snapshot.rawBytes);
		assertEquals(100, snapshot.wireBytes);
		NetworkSnapshot.SourceEntry stone = find(snapshot.sources, STONE);
		NetworkSnapshot.SourceEntry pipe = find(snapshot.sources, PIPE);
		assertEquals(300, stone.rawBytes());
		assertEquals(75, stone.wireBytes());
		assertEquals(100, pipe.rawBytes());
		assertEquals(25, pipe.wireBytes());
		// The chunk gets the whole packet, once per share.
		assertEquals(100, snapshot.chunks.get(0).wireBytes());
		assertEquals(STONE, snapshot.chunks.get(0).topSource());
		assertEquals(3, snapshot.chunks.get(0).x());
		assertEquals(-2, snapshot.chunks.get(0).z());
		assertEquals(SPOT, snapshot.chunks.get(0).spot());
	}

	@Test
	void modsAndPacketTypesAreSortedBySentBytes() {
		NetworkWindow window = new NetworkWindow();
		window.recordWire(window.recordRaw(ENTITY_DATA, one(ZOMBIE), 1, 50), 50);
		window.recordWire(window.recordRaw(ENTITY_DATA, one(ZOMBIE), 1, 50), 50);
		window.recordWire(window.recordRaw(BLOCK_UPDATES, one(PIPE), 1, 500), 300);

		NetworkSnapshot snapshot = window.snapshot("test");
		assertEquals("pipes", snapshot.mods().get(0).namespace());
		assertEquals(300, snapshot.mods().get(0).wireBytes());
		assertEquals("minecraft", snapshot.mods().get(1).namespace());
		assertEquals(2, snapshot.mods().get(1).packets());
		assertEquals("NetworkWindowTest.SectionBlocksUpdate", snapshot.packetTypes.get(0).id());
		assertEquals("NetworkWindowTest.SetEntityData", find(snapshot.sources, ZOMBIE).topPackets().get(0).packet());
		assertEquals(75, snapshot.percent(300), 0.001);
		assertEquals(100 - 400 * 100.0 / 600, snapshot.compressionSaving(), 0.001);
	}

	@Test
	void countsFromManyThreadsAreNotLost() throws InterruptedException {
		NetworkWindow window = new NetworkWindow();
		int threads = 8;
		int perThread = 10_000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		for (int t = 0; t < threads; t++) {
			pool.execute(() -> {
				try {
					start.await();
				} catch (InterruptedException e) {
					return;
				}
				for (int i = 0; i < perThread; i++) {
					window.recordWire(window.recordRaw(ENTITY_DATA, one(ZOMBIE), 1, 10), 7);
				}
			});
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

		NetworkSnapshot snapshot = window.snapshot("test");
		assertEquals((long) threads * perThread, snapshot.packets);
		assertEquals(10L * threads * perThread, snapshot.rawBytes);
		assertEquals(7L * threads * perThread, find(snapshot.sources, ZOMBIE).wireBytes());
	}

	@Test
	void wireSizeCountsTheLengthPrefix() {
		assertEquals(1 + 1, NetworkProfiler.framed(1));
		assertEquals(127 + 1, NetworkProfiler.framed(127));
		assertEquals(128 + 2, NetworkProfiler.framed(128));
		assertEquals(16_384 + 3, NetworkProfiler.framed(16_384));
	}

	@Test
	void bytesAreFormattedInBinaryUnits() {
		assertEquals("512 B", NetworkSnapshot.formatBytes(512));
		assertEquals("1.50 KB", NetworkSnapshot.formatBytes(1536));
		assertEquals("2.00 MB", NetworkSnapshot.formatBytes(2 * 1024 * 1024));
	}

	private static NetworkSnapshot.SourceEntry find(List<NetworkSnapshot.SourceEntry> entries, NetworkSource source) {
		return entries.stream().filter(entry -> entry.source().equals(source)).findFirst().orElseThrow();
	}
}
