package com.studioderiva.tickengine.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Tick time by chunk, without a server. Types are plain objects, so they show up as unregistered. */
class ChunkTimesTest {
	// Level.OVERWORLD would initialize Level, which needs a bootstrapped game.
	private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
	private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether"));
	private static final Object HOPPER = new Object();
	private static final Object CHICKEN = new Object();
	private static final Object REPEATER = new Object();

	@Test
	void timeAddsUpPerChunkAndDimension() {
		ProfileWindow window = new ProfileWindow();
		window.addTick(20_000_000);
		window.add(ProfileSnapshot.Category.BLOCK_ENTITY, HOPPER, 3_000_000, OVERWORLD, new BlockPos(1, 64, 1));
		window.add(ProfileSnapshot.Category.ENTITY, CHICKEN, 600_000, OVERWORLD, new BlockPos(15, 70, 15));
		window.add(ProfileSnapshot.Category.ENTITY, CHICKEN, 400_000, OVERWORLD, new BlockPos(8, 70, 8));
		// One block west is the next chunk: -1 >> 4 is -1.
		window.add(ProfileSnapshot.Category.BLOCK_TICK, REPEATER, 2_000_000, OVERWORLD, new BlockPos(-1, 64, 0));
		window.add(ProfileSnapshot.Category.BLOCK_ENTITY, HOPPER, 5_000_000, NETHER, new BlockPos(1, 64, 1));
		// Chunk work has no place and stays out of the ranking.
		window.add(ProfileSnapshot.Category.CHUNK, ChunkWork.UNLOAD, 9_000_000, null, null);

		List<ProfileSnapshot.ChunkEntry> chunks = window.snapshot("test", null).chunkTimes;
		assertEquals(3, chunks.size());

		ProfileSnapshot.ChunkEntry nether = chunks.get(0);
		assertEquals(NETHER, nether.dimension());
		assertEquals(5_000_000, nether.totalNanos());

		ProfileSnapshot.ChunkEntry farm = chunks.get(1);
		assertEquals(OVERWORLD, farm.dimension());
		assertEquals(0, farm.x());
		assertEquals(0, farm.z());
		assertEquals(4_000_000, farm.totalNanos());
		assertEquals(3_000_000, farm.nanos(ProfileSnapshot.Category.BLOCK_ENTITY));
		assertEquals(1_000_000, farm.nanos(ProfileSnapshot.Category.ENTITY));
		assertEquals(0, farm.blockNanos());
		assertEquals(new BlockPos(1, 64, 1), farm.worstPos(), "the slowest single call");
		assertEquals(ProfileSnapshot.Category.BLOCK_ENTITY, farm.worstCategory());
		assertEquals(2, farm.topTypes().size());
		assertEquals(3_000_000, farm.topTypes().get(0).nanos(), "hopper first");
		assertEquals(1_000_000, farm.topTypes().get(1).nanos(), "both chickens in one row");

		ProfileSnapshot.ChunkEntry west = chunks.get(2);
		assertEquals(-1, west.x());
		assertEquals(0, west.z());
		assertEquals(2_000_000, west.blockNanos());
	}

	@Test
	void topTypesAreCapped() {
		ProfileWindow window = new ProfileWindow();
		window.addTick(1_000_000);
		for (int i = 0; i < ProfileWindow.CHUNK_TOP_TYPES + 2; i++) {
			window.add(ProfileSnapshot.Category.ENTITY, new Object(), 1000 + i, OVERWORLD, BlockPos.ZERO);
		}
		ProfileSnapshot.ChunkEntry chunk = window.snapshot("test", null).chunkTimes.get(0);
		assertEquals(ProfileWindow.CHUNK_TOP_TYPES, chunk.topTypes().size());
		assertTrue(chunk.topTypes().get(0).nanos() >= chunk.topTypes().get(1).nanos());
	}
}
