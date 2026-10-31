package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.playerAt;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.profiler.ChunkWork;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Scheduled block and fluid ticks, block events, random ticks and chunk work reach the profiler. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProfilerCoverageTests {
	private ProfilerCoverageTests() {
	}

	private static boolean has(ProfileSnapshot snapshot, ProfileSnapshot.Category category, String id) {
		return snapshot.entries(category).stream().anyMatch(entry -> entry.id().equals(id) && entry.calls() > 0);
	}

	/** An observer clock driving a piston, flowing water and wheat on farmland with fast random ticks. */
	@GameTest(template = "floor", batch = "profiler_blocks", timeoutTicks = 300)
	public static void blocksReachTheProfiler(GameTestHelper helper) {
		configure(helper);
		TickProfiler.reset();
		ServerLevel level = helper.getLevel();
		GameRules.IntegerValue randomTicks = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
		int oldRandomTicks = randomTicks.get();
		randomTicks.set(4096, level.getServer());
		// 1.20.1 random ticks only chunks near a player.
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		ServerPlayer player = playerAt(helper, origin.getX() + 8, origin.getZ() + 8);

		helper.setBlock(new BlockPos(1, 2, 1), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
		helper.setBlock(new BlockPos(2, 2, 1), Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
		helper.setBlock(new BlockPos(0, 2, 1), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
		helper.setBlock(new BlockPos(3, 2, 1), Blocks.REDSTONE_WIRE);
		for (int x = 0; x < 4; x++) {
			helper.setBlock(new BlockPos(x, 1, 8), Blocks.FARMLAND);
			helper.setBlock(new BlockPos(x, 2, 8), Blocks.WHEAT);
		}
		helper.setBlock(new BlockPos(10, 4, 10), Blocks.WATER);

		AtomicReference<ProfileSnapshot> result = new AtomicReference<>();
		check(TickProfiler.startSession(60, result::set), "session did not start");
		helper.runAfterDelay(60, () -> {
			TickProfiler.stopSession();
			randomTicks.set(oldRandomTicks, level.getServer());
			player.remove(Entity.RemovalReason.DISCARDED);
			ProfileSnapshot snapshot = result.get();
			check(snapshot != null && snapshot.sampledTicks >= 50, "session sampled too few ticks");
			check(has(snapshot, ProfileSnapshot.Category.BLOCK_TICK, "minecraft:observer"), "observer ticks not profiled: "
				+ snapshot.blockEntries());
			check(has(snapshot, ProfileSnapshot.Category.BLOCK_EVENT, "minecraft:piston"), "piston events not profiled: "
				+ snapshot.blockEntries());
			check(has(snapshot, ProfileSnapshot.Category.FLUID_TICK, "minecraft:flowing_water")
				|| has(snapshot, ProfileSnapshot.Category.FLUID_TICK, "minecraft:water"), "water ticks not profiled: "
				+ snapshot.blockEntries());
			check(has(snapshot, ProfileSnapshot.Category.RANDOM_TICK, "minecraft:wheat")
				|| has(snapshot, ProfileSnapshot.Category.RANDOM_TICK, "minecraft:farmland"), "random ticks not profiled: "
				+ snapshot.blockEntries());
			check(snapshot.mods().stream().anyMatch(mod -> mod.namespace().equals("minecraft")), "blocks not counted for their mod");
			helper.succeed();
		});
	}

	/** Asking for a chunk nobody generated makes the server thread wait, generate and finish it. */
	@GameTest(template = "floor", batch = "profiler_chunks", timeoutTicks = 300)
	public static void chunkWorkReachesTheProfiler(GameTestHelper helper) {
		configure(helper);
		TickProfiler.reset();
		ServerLevel level = helper.getLevel();
		AtomicReference<ProfileSnapshot> result = new AtomicReference<>();
		check(TickProfiler.startSession(60, result::set), "session did not start");
		helper.runAfterDelay(5, () -> {
			// Far from anything the test world loaded; random per run, so the chunk is never saved yet.
			int chunkX = 20_000 + level.getRandom().nextInt(10_000);
			level.getChunk(chunkX, 20_000, ChunkStatus.FULL, true);
		});
		helper.runAfterDelay(40, () -> {
			TickProfiler.stopSession();
			ProfileSnapshot snapshot = result.get();
			check(snapshot != null, "no session result");
			check(snapshot.chunks.created() > 0, "no chunk started generating: " + snapshot.chunks);
			check(snapshot.chunks.generated() > 0, "no chunk finished generating: " + snapshot.chunks);
			check(snapshot.entries(ProfileSnapshot.Category.CHUNK).stream()
				.anyMatch(entry -> entry.id().equals(ChunkWork.WAIT.displayName) && entry.totalNanos() > 0),
				"waiting for the chunk not timed: " + snapshot.entries(ProfileSnapshot.Category.CHUNK));
			check(snapshot.mods().stream().noneMatch(mod -> mod.totalNanos() == 0 && mod.types() == 0), "empty mod rows");
			helper.succeed();
		});
	}
}
