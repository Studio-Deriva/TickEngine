package com.studioderiva.tickengine.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Chunk work timing and the new snapshot categories, without a server. */
class TickProfilerChunkTest {
	@AfterEach
	void reset() {
		TickProfiler.reset();
	}

	private static void busy(long nanos) {
		long end = System.nanoTime() + nanos;
		while (System.nanoTime() < end) {
			Thread.onSpinWait();
		}
	}

	/** Sessions hand their result to this listener when they stop. */
	private static final AtomicReference<ProfileSnapshot> LISTENED = new AtomicReference<>();

	private static ProfileSnapshot stop() {
		assertTrue(TickProfiler.stopSession());
		return LISTENED.get();
	}

	private static long nanos(ProfileSnapshot snapshot, ChunkWork work) {
		return snapshot.entries(ProfileSnapshot.Category.CHUNK).stream()
			.filter(entry -> entry.id().equals(work.displayName)).mapToLong(ProfileSnapshot.Entry::totalNanos).sum();
	}

	@Test
	void nestedChunkWorkIsCountedOnce() {
		assertTrue(TickProfiler.startSession(60, LISTENED::set));
		TickProfiler.sampling = true;

		// Waiting 2 ms, of which a 5 ms task ran inside (tasks run while the server waits for a chunk).
		long waitInner = TickProfiler.chunkWorkInner();
		long waitStart = System.nanoTime();
		busy(2_000_000);
		long taskInner = TickProfiler.chunkWorkInner();
		long taskStart = System.nanoTime();
		busy(5_000_000);
		TickProfiler.endChunkWork(ChunkWork.TASKS, taskStart, taskInner);
		TickProfiler.endChunkWork(ChunkWork.WAIT, waitStart, waitInner);
		long total = System.nanoTime() - waitStart;

		TickProfiler.sampling = false;
		ProfileSnapshot snapshot = stop();
		long wait = nanos(snapshot, ChunkWork.WAIT);
		long task = nanos(snapshot, ChunkWork.TASKS);
		assertTrue(task >= 5_000_000, "task " + task);
		assertTrue(wait >= 2_000_000 && wait < 5_000_000, "wait without the nested task, got " + wait);
		assertTrue(wait + task <= total, "rows must not add up to more than the elapsed time");
	}

	@Test
	void chunkWorkOutsideTicksIsTimeBetweenTicks() {
		assertTrue(TickProfiler.startSession(60, LISTENED::set));
		long inner = TickProfiler.chunkWorkInner();
		long start = System.nanoTime();
		busy(1_000_000);
		TickProfiler.endChunkWork(ChunkWork.TASKS, start, inner);

		ProfileSnapshot snapshot = stop();
		assertTrue(snapshot.entries(ProfileSnapshot.Category.CHUNK).isEmpty(), "not tick time");
		assertTrue(snapshot.chunks.betweenTickNanos() >= 1_000_000);
	}

	@Test
	void chunkCountsNeedTheServerThread() {
		assertTrue(TickProfiler.startSession(60, LISTENED::set));
		// No tick ran, so this thread is not the server thread: counts from other threads are dropped.
		TickProfiler.chunkLoaded();
		TickProfiler.chunkCreated();
		ProfileSnapshot snapshot = stop();
		assertEquals(0, snapshot.chunks.loaded());
		assertEquals(0, snapshot.chunks.created());
	}

	@Test
	void chunkWorkIsNotAMod() {
		ProfileSnapshot.Entry chunk = new ProfileSnapshot.Entry(ProfileSnapshot.Category.CHUNK, ChunkWork.UNLOAD.displayName,
			"minecraft", 9_000_000, 1, 9_000_000, null, null);
		ProfileSnapshot.Entry repeater = new ProfileSnapshot.Entry(ProfileSnapshot.Category.BLOCK_TICK, "minecraft:repeater",
			"minecraft", 3_000_000, 40, 200_000, null, null);
		ProfileSnapshot.Entry pipe = new ProfileSnapshot.Entry(ProfileSnapshot.Category.BLOCK_EVENT, "pipes:piston",
			"pipes", 1_000_000, 4, 400_000, null, null);
		ProfileSnapshot.Entry zombie = new ProfileSnapshot.Entry(ProfileSnapshot.Category.ENTITY, "minecraft:zombie",
			"minecraft", 2_000_000, 10, 300_000, null, null);
		ProfileSnapshot snapshot = new ProfileSnapshot("test", 0, 1000, 20, 100_000_000,
			List.of(chunk, repeater, pipe, zombie), ChunkActivity.EMPTY, List.of());

		List<ProfileSnapshot.ModEntry> mods = snapshot.mods();
		assertEquals(2, mods.size());
		assertEquals("minecraft", mods.get(0).namespace());
		assertEquals(5_000_000, mods.get(0).totalNanos(), "repeater and zombie, not chunk saving");
		assertEquals(List.of(repeater, pipe), snapshot.blockEntries(), "block categories only, slowest first");
		assertEquals(20.0, snapshot.perSecond(20), 1e-9);
	}
}
