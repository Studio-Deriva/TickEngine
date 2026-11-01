package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.litFurnace;
import static com.studioderiva.tickengine.gametest.TestSupport.playerAt;
import static com.studioderiva.tickengine.gametest.TestSupport.run;
import static com.studioderiva.tickengine.gametest.TestSupport.spawnPlain;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.history.HistoryEntry;
import com.studioderiva.tickengine.history.HistoryStore;
import com.studioderiva.tickengine.limit.EntityLimit;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.tuning.AutoTuner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Chunks ranked by tick time, the entity limit per chunk and the history. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LimitAndHistoryTests {
	private LimitAndHistoryTests() {
	}

	/** The test area and a margin around it, for counting what spawned there. */
	private static AABB area(GameTestHelper helper) {
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		return new AABB(origin).inflate(24);
	}

	private static <E extends Entity> List<E> inArea(GameTestHelper helper, EntityType<E> type) {
		return helper.getLevel().getEntities(type, area(helper), Entity::isAlive);
	}

	/** Every timed block entity, entity and block is counted for its chunk, and the cows' chunk names them. */
	@GameTest(template = "floor", batch = "chunk_ranking", timeoutTicks = 200)
	public static void chunksAreRankedByTickTime(GameTestHelper helper) {
		configure(helper);
		TickProfiler.reset();
		Cow cow = spawnPlain(helper, EntityType.COW, 3, 2, 3);
		for (int i = 0; i < 3; i++) {
			spawnPlain(helper, EntityType.COW, 3 + i * 0.3, 2, 3);
		}
		litFurnace(helper, new BlockPos(6, 2, 6));
		AtomicReference<ProfileSnapshot> result = new AtomicReference<>();
		check(TickProfiler.startSession(60, result::set), "session did not start");
		helper.runAfterDelay(40, () -> {
			TickProfiler.stopSession();
			ProfileSnapshot snapshot = result.get();
			check(!snapshot.chunkTimes.isEmpty(), "no chunk was ranked");
			long typed = snapshot.entries.stream().filter(entry -> entry.category() != ProfileSnapshot.Category.CHUNK)
				.mapToLong(ProfileSnapshot.Entry::totalNanos).sum();
			long chunks = snapshot.chunkTimes.stream().mapToLong(ProfileSnapshot.ChunkEntry::totalNanos).sum();
			check(typed == chunks, "chunk times " + chunks + " do not add up to the timed types " + typed);
			for (int i = 1; i < snapshot.chunkTimes.size(); i++) {
				check(snapshot.chunkTimes.get(i - 1).totalNanos() >= snapshot.chunkTimes.get(i).totalNanos(), "not sorted");
			}
			ChunkPos cowChunk = cow.chunkPosition();
			ProfileSnapshot.ChunkEntry entry = snapshot.chunkTimes.stream()
				.filter(chunk -> chunk.dimension() == helper.getLevel().dimension() && chunk.x() == cowChunk.x
					&& chunk.z() == cowChunk.z)
				.findFirst().orElse(null);
			check(entry != null, "the cows' chunk is not ranked");
			check(entry.nanos(ProfileSnapshot.Category.ENTITY) > 0, "no entity time in the cows' chunk");
			check(entry.topTypes().stream().anyMatch(type -> type.id().equals("minecraft:cow")),
				"the cows' chunk does not name them: " + entry.topTypes());
			helper.succeed();
		});
	}

	/** A spawner next to a player stops at 3 pigs per chunk; without the limit it would place up to 30. */
	@GameTest(template = "floor", batch = "limit_spawner", timeoutTicks = 400)
	public static void spawnerStopsAtTheLimit(GameTestHelper helper) {
		configure(helper, "entityLimit.enabled", "true");
		TickEngineConfig.LIMIT_RULES.set("minecraft:pig", 3, null);
		BlockPos spawner = helper.absolutePos(new BlockPos(4, 2, 4));
		ServerPlayer player = playerAt(helper, spawner.getX() + 0.5, spawner.getZ() + 3.5);
		// Custom spawn rules: any light, any floor, so the floor and the time of day do not matter. No AI:
		// the limit counts mobs where they spawn, and pigs walking into the next chunk would blur that.
		run(helper, "setblock " + spawner.getX() + " " + spawner.getY() + " " + spawner.getZ()
			+ " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:pig\",NoAI:1b},custom_spawn_rules:{block_light_limit:"
			+ "{min_inclusive:0,max_inclusive:15},sky_light_limit:{min_inclusive:0,max_inclusive:15}}},"
			+ "MinSpawnDelay:10,MaxSpawnDelay:20,SpawnCount:4,MaxNearbyEntities:30,RequiredPlayerRange:32,SpawnRange:2,Delay:5}");
		long blockedBefore = EntityLimit.blocked(EntityLimit.Source.SPAWNER);
		helper.runAfterDelay(200, () -> {
			player.remove(Entity.RemovalReason.DISCARDED);
			List<Pig> pigs = inArea(helper, EntityType.PIG);
			Map<Long, Integer> perChunk = new HashMap<>();
			pigs.forEach(pig -> perChunk.merge(pig.chunkPosition().toLong(), 1, Integer::sum));
			check(pigs.size() >= 3, "the spawner placed only " + pigs.size() + " pigs");
			check(perChunk.values().stream().allMatch(count -> count <= 3), "a chunk has more than 3 pigs: " + perChunk);
			check(EntityLimit.blocked(EntityLimit.Source.SPAWNER) > blockedBefore, "the limit never stopped the spawner");
			pigs.forEach(Entity::discard);
			helper.succeed();
		});
	}

	/** Two cows in love make no calf in a chunk that already has its limit of cows. */
	@GameTest(template = "floor", batch = "limit_breeding", timeoutTicks = 400)
	public static void breedingStopsAtTheLimit(GameTestHelper helper) {
		configure(helper, "entityLimit.enabled", "true");
		TickEngineConfig.LIMIT_RULES.set("minecraft:cow", 2, null);
		Cow first = spawnPlain(helper, EntityType.COW, 4, 2, 4);
		Cow second = spawnPlain(helper, EntityType.COW, 4.5, 2, 4);
		first.setInLove(null);
		second.setInLove(null);
		long blockedBefore = EntityLimit.blocked(EntityLimit.Source.BREEDING);
		helper.succeedWhen(() -> {
			check(EntityLimit.blocked(EntityLimit.Source.BREEDING) > blockedBefore, "no breeding stopped yet");
			check(inArea(helper, EntityType.COW).size() == 2, "a calf was born");
			check(!first.isInLove() && !second.isInLove(), "the cows are still in love");
		});
	}

	/** Thrown eggs hatch no chicks in a chunk at the chicken limit. */
	@GameTest(template = "floor", batch = "limit_eggs", timeoutTicks = 200)
	public static void eggsHatchNoChicksAtTheLimit(GameTestHelper helper) {
		configure(helper, "entityLimit.enabled", "true");
		TickEngineConfig.LIMIT_RULES.set("minecraft:chicken", 1, null);
		spawnPlain(helper, EntityType.CHICKEN, 4, 2, 4);
		ServerLevel level = helper.getLevel();
		long blockedBefore = EntityLimit.blocked(EntityLimit.Source.BREEDING);
		// One egg in 8 hatches: with 60 eggs the chance that none tries is below 0.1%.
		for (int i = 0; i < 60; i++) {
			Vec3 pos = helper.absoluteVec(new Vec3(4.5, 6, 4.5));
			level.addFreshEntity(new ThrownEgg(level, pos.x, pos.y, pos.z));
		}
		helper.runAfterDelay(60, () -> {
			check(inArea(helper, EntityType.CHICKEN).size() == 1, "chicks hatched: " + inArea(helper, EntityType.CHICKEN).size());
			check(EntityLimit.blocked(EntityLimit.Source.BREEDING) > blockedBefore, "no hatching was stopped");
			helper.succeed();
		});
	}

	/**
	 * Auto-tuning above the peak level makes the period a peak, with the light profiler's heaviest things.
	 * Periods follow the wall clock and the GameTest server ticks as fast as it can, hence the many ticks.
	 */
	@GameTest(template = "floor", batch = "history", timeoutTicks = 400_000)
	public static void historyKeepsPeaks(GameTestHelper helper) {
		// Auto-tuning on, or it falls back to level 0 at once; forcing a level also pauses it.
		configure(helper, "autoTuning.enabled", "true", "profiler.windowSeconds", "10", "history.peakTuningLevel", "2");
		long start = System.currentTimeMillis();
		AutoTuner.forceLevel(helper.getLevel().getServer(), 3);
		spawnPlain(helper, EntityType.COW, 4, 2, 4);
		AtomicReference<CompletableFuture<List<HistoryEntry>>> read = new AtomicReference<>();
		helper.succeedWhen(() -> {
			if (read.get() == null || read.get().isDone() && !hasPeak(read.get().join())) {
				read.set(HistoryStore.read(start, System.currentTimeMillis() + 1000));
			}
			check(read.get().isDone() && hasPeak(read.get().join()), "no peak in the history yet");
			AutoTuner.forceLevel(helper.getLevel().getServer(), 0);
			AutoTuner.resume();
		});
	}

	/** The new commands answer without errors and say what they should. */
	@GameTest(template = "floor", batch = "limit_commands", timeoutTicks = 200)
	public static void commandsAnswer(GameTestHelper helper) {
		configure(helper);
		spawnPlain(helper, EntityType.COW, 4, 2, 4);
		AtomicReference<ProfileSnapshot> result = new AtomicReference<>();
		TickProfiler.reset();
		check(TickProfiler.startSession(60, result::set), "session did not start");
		helper.runAfterDelay(30, () -> {
			TickProfiler.stopSession();
			TestSupport.CommandResult limit = run(helper, "tickengine limit rules set 5 minecraft:cow");
			check(limit.result() == 1 && limit.text().contains("at most 5 per chunk"), limit.text());
			TestSupport.CommandResult status = run(helper, "tickengine limit");
			check(status.text().contains("Entity limit per chunk") && status.text().contains("natural spawning, spawners, breeding"),
				status.text());
			check(run(helper, "tickengine limit enable").text().contains("Entity limit is on"), "enable");
			check(run(helper, "tickengine limit exclude add minecraft:villager").result() == 1, "exclude add");
			check(run(helper, "tickengine limit exclude list").text().contains("minecraft:villager"), "exclude list");
			check(run(helper, "tickengine limit rules list").text().contains("minecraft:cow"), "rules list");
			check(run(helper, "tickengine limit disable").text().contains("Entity limit is off"), "disable");
			check(run(helper, "tickengine history 1").result() == 1, "history");
			check(run(helper, "tickengine history report 1").result() == 1, "history report");
			check(run(helper, "tickengine history peak 1").result() == 1, "history peak");
			check(run(helper, "tickengine top worldgen").text().contains("Chunk loading and worldgen"), "top worldgen");
			helper.succeed();
		});
	}

	private static boolean hasPeak(List<HistoryEntry> entries) {
		return entries.stream().anyMatch(entry -> entry.kind().equals(HistoryEntry.PERIOD)
			&& entry.peaks().contains(HistoryEntry.TUNING) && entry.tuningLevel() >= 3 && entry.detail() != null
			&& !entry.detail().mods().isEmpty());
	}
}
