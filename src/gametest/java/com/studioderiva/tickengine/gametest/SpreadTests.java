package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.run;
import static com.studioderiva.tickengine.gametest.TestSupport.spawnPlain;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** entities.spreadTicks: the confirmation flow and its effect on entities loaded together. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpreadTests {
	private static final int COWS = 12;

	private SpreadTests() {
	}

	@GameTest(template = "floor", batch = "spread_confirmation", timeoutTicks = 40)
	public static void turningOnNeedsTheConfirmationPhrase(GameTestHelper helper) {
		configure(helper);
		check(run(helper, "/tickengine config set entities.spreadTicks true").result() == 0,
			"config set must not turn spreading on");
		check(!TickEngineConfig.spreadEntityTicks, "config set turned spreading on");
		check(run(helper, "/tickengine spread confirm I understand the risks").result() == 0,
			"confirming without '/tickengine spread enable' first must fail");
		TestSupport.CommandResult enable = run(helper, "/tickengine spread enable");
		check(enable.result() == 1 && enable.text().contains("Risk") && enable.text().contains("Gain"),
			"enable must explain gain and risk: " + enable.text());
		check(!TickEngineConfig.spreadEntityTicks, "enable alone turned spreading on");
		check(run(helper, "/tickengine spread confirm yes").result() == 0, "a wrong phrase was accepted");
		check(!TickEngineConfig.spreadEntityTicks, "a wrong phrase turned spreading on");
		check(run(helper, "/tickengine spread confirm I understand the risks").result() == 1, "the right phrase was refused");
		check(TickEngineConfig.spreadEntityTicks, "spreading is not on after confirming");
		check(run(helper, "/tickengine spread").text().contains("on"), "status does not show it on");
		check(run(helper, "/tickengine spread confirm I understand the risks").result() == 0,
			"a confirmation can only be used once");
		check(run(helper, "/tickengine spread disable").result() == 1 && !TickEngineConfig.spreadEntityTicks, "disable");
		configure(helper);
		helper.succeed();
	}

	private static List<Cow> cowsSpawnedTogether(GameTestHelper helper) {
		List<Cow> cows = new ArrayList<>();
		for (int i = 0; i < COWS; i++) {
			cows.add(spawnPlain(helper, EntityType.COW, 2.5 + i, 2, 8.5));
		}
		return cows;
	}

	/**
	 * Watches the cows' tick counters every game tick and adds up how far each one jumped beyond
	 * the normal +1. Measuring jumps instead of absolute values keeps the test independent of
	 * when each cow's chunk started ticking.
	 */
	private static int[] watchJumps(GameTestHelper helper, List<? extends Entity> cows) {
		int[] jumps = new int[cows.size()];
		int[] last = cows.stream().mapToInt(cow -> cow.tickCount).toArray();
		helper.onEachTick(() -> {
			for (int i = 0; i < cows.size(); i++) {
				int now = cows.get(i).tickCount;
				if (now - last[i] > 1) {
					jumps[i] += now - last[i] - 1;
				}
				last[i] = now;
			}
		});
		return jumps;
	}

	private static Set<Integer> offsetPhases(List<Cow> cows, int interval) {
		Set<Integer> phases = new HashSet<>();
		cows.forEach(cow -> phases.add(EntityThrottle.spreadOffset(cow) % interval));
		return phases;
	}

	@GameTest(template = "floor", batch = "spread_off", timeoutTicks = 200)
	public static void withoutSpreadingCountersNeverJump(GameTestHelper helper) {
		configure(helper);
		List<Cow> cows = cowsSpawnedTogether(helper);
		int[] jumps = watchJumps(helper, cows);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 20, () -> {
			for (int i = 0; i < cows.size(); i++) {
				check(jumps[i] == 0, "cow " + cows.get(i).getId() + " jumped by " + jumps[i] + " with spreading off");
			}
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_on", timeoutTicks = 200)
	public static void spreadingMovesEachCounterOnceByItsOffset(GameTestHelper helper) {
		configure(helper, "entities.spreadTicks", "true");
		List<Cow> cows = cowsSpawnedTogether(helper);
		int[] jumps = watchJumps(helper, cows);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 40, () -> {
			for (int i = 0; i < cows.size(); i++) {
				Cow cow = cows.get(i);
				check(jumps[i] == EntityThrottle.spreadOffset(cow), "cow " + cow.getId() + " jumped by " + jumps[i]
					+ ", expected " + EntityThrottle.spreadOffset(cow));
			}
			check(offsetPhases(cows, 4).size() >= 3, "offsets modulo 4 not spread: " + offsetPhases(cows, 4));
			configure(helper);
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_confirmation", timeoutTicks = 20)
	public static void spreadingIsOnByDefault(GameTestHelper helper) {
		check("true".equals(TestSupport.defaultValue("entities.spreadTicks")),
			"entities.spreadTicks should default to true, is " + TestSupport.defaultValue("entities.spreadTicks"));
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "spread_throttle_off", timeoutTicks = 200)
	public static void spreadingWaitsForEntityThrottling(GameTestHelper helper) {
		configure(helper, "entities.spreadTicks", "true", "entities.enabled", "false");
		List<Cow> cows = cowsSpawnedTogether(helper);
		int[] jumps = watchJumps(helper, cows);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 20, () -> {
			for (int i = 0; i < cows.size(); i++) {
				check(jumps[i] == 0, "cow " + cows.get(i).getId() + " jumped by " + jumps[i]
					+ " with entity throttling off");
			}
			configure(helper);
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_loaded", timeoutTicks = 260)
	public static void confirmingSpreadsEntitiesAlreadyLoaded(GameTestHelper helper) {
		configure(helper);
		List<Cow> cows = cowsSpawnedTogether(helper);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 20, () -> {
			int[] before = cows.stream().mapToInt(cow -> cow.tickCount).toArray();
			run(helper, "/tickengine spread enable");
			check(run(helper, "/tickengine spread confirm I understand the risks").result() == 1, "confirm");
			for (int i = 0; i < cows.size(); i++) {
				Cow cow = cows.get(i);
				if (before[i] > EntityThrottle.SPREAD_AT_TICK) {
					check(cow.tickCount - before[i] == EntityThrottle.spreadOffset(cow), "loaded cow " + cow.getId()
						+ " moved by " + (cow.tickCount - before[i]) + ", expected " + EntityThrottle.spreadOffset(cow));
				}
			}
			configure(helper);
			helper.succeed();
		});
	}

	/** Spawns pigs next to the cows, so one test can compare a type that is spread with one that is not. */
	private static List<Pig> pigsSpawnedTogether(GameTestHelper helper) {
		List<Pig> pigs = new ArrayList<>();
		for (int i = 0; i < COWS; i++) {
			pigs.add(spawnPlain(helper, EntityType.PIG, 2.5 + i, 2, 4.5));
		}
		return pigs;
	}

	private static void checkNotMoved(List<? extends Entity> entities, int[] jumps, String why) {
		for (int i = 0; i < entities.size(); i++) {
			check(jumps[i] == 0, entities.get(i).getType().getDescriptionId() + " " + entities.get(i).getId()
				+ " jumped by " + jumps[i] + " although " + why);
		}
	}

	private static void checkMovedByOffset(List<? extends Entity> entities, int[] jumps) {
		for (int i = 0; i < entities.size(); i++) {
			Entity entity = entities.get(i);
			check(jumps[i] == EntityThrottle.spreadOffset(entity), entity.getType().getDescriptionId() + " " + entity.getId()
				+ " jumped by " + jumps[i] + ", expected " + EntityThrottle.spreadOffset(entity));
		}
	}

	/** Cows and pigs spawned together; checks run once both have passed the spreading tick. */
	private static void spreadCowsAndPigs(GameTestHelper helper, Runnable setup, String cowWhy) {
		setup.run();
		List<Cow> cows = cowsSpawnedTogether(helper);
		List<Pig> pigs = pigsSpawnedTogether(helper);
		int[] cowJumps = watchJumps(helper, cows);
		int[] pigJumps = watchJumps(helper, pigs);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 40, () -> {
			checkNotMoved(cows, cowJumps, cowWhy);
			checkMovedByOffset(pigs, pigJumps);
			configure(helper);
			TestSupport.run(helper, "/tickengine rules entities remove minecraft:cow");
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_exclude_type", timeoutTicks = 220)
	public static void excludedTypeIsNotMoved(GameTestHelper helper) {
		spreadCowsAndPigs(helper, () -> {
			configure(helper, "entities.spreadTicks", "true");
			check(run(helper, "/tickengine spread exclude add minecraft:cow").result() == 1, "exclude add");
		}, "cows are excluded");
	}

	@GameTest(template = "floor", batch = "spread_exclude_mod", timeoutTicks = 220)
	public static void excludedModIsNotMoved(GameTestHelper helper) {
		configure(helper, "entities.spreadTicks", "true");
		check(run(helper, "/tickengine spread exclude add minecraft:*").result() == 1, "exclude add");
		List<Cow> cows = cowsSpawnedTogether(helper);
		List<Pig> pigs = pigsSpawnedTogether(helper);
		int[] cowJumps = watchJumps(helper, cows);
		int[] pigJumps = watchJumps(helper, pigs);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 40, () -> {
			checkNotMoved(cows, cowJumps, "the whole minecraft namespace is excluded");
			checkNotMoved(pigs, pigJumps, "the whole minecraft namespace is excluded");
			configure(helper);
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_never_throttled", timeoutTicks = 220)
	public static void neverThrottledTypeIsNotMoved(GameTestHelper helper) {
		spreadCowsAndPigs(helper, () -> {
			configure(helper, "entities.spreadTicks", "true");
			check(run(helper, "/tickengine rules entities set 1 1 minecraft:cow").result() == 1, "cow rule");
		}, "cows are never throttled (rule 1 1)");
	}

	@GameTest(template = "floor", batch = "spread_ai_only_rule", timeoutTicks = 220)
	public static void typeThrottledInOneZoneIsStillMoved(GameTestHelper helper) {
		configure(helper, "entities.spreadTicks", "true");
		check(run(helper, "/tickengine rules entities set 1 4 minecraft:cow").result() == 1, "cow rule");
		List<Cow> cows = cowsSpawnedTogether(helper);
		int[] jumps = watchJumps(helper, cows);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 40, () -> {
			checkMovedByOffset(cows, jumps);
			configure(helper);
			TestSupport.run(helper, "/tickengine rules entities remove minecraft:cow");
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "spread_exclude_commands", timeoutTicks = 40)
	public static void excludeCommandsCheckSaveAndReload(GameTestHelper helper) {
		configure(helper);
		check(run(helper, "/tickengine spread exclude add nonsense").result() == 0, "an invalid target was accepted");
		check(run(helper, "/tickengine spread exclude add *").result() == 0, "'*' was accepted outside a dimension");
		check(TickEngineConfig.SPREAD_EXCLUDE.targets().isEmpty(), "a rejected target was stored");
		check(run(helper, "/tickengine spread exclude add minecraft:villager").result() == 1, "add a type");
		check(run(helper, "/tickengine spread exclude add somemod:*").result() == 1, "add a mod");
		TestSupport.CommandResult twice = run(helper, "/tickengine spread exclude add minecraft:villager");
		check(twice.result() == 1 && twice.text().contains("already"), "adding twice: " + twice.text());
		check(TickEngineConfig.SPREAD_EXCLUDE.targets().size() == 2, "list: " + TickEngineConfig.SPREAD_EXCLUDE.targets());
		TestSupport.CommandResult list = run(helper, "/tickengine spread exclude list");
		check(list.text().contains("minecraft:villager") && list.text().contains("somemod:*"), "list: " + list.text());
		check(run(helper, "/tickengine spread").text().contains("minecraft:villager"), "spread status does not show exclusions");
		check(run(helper, "/tickengine config reload").result() == 1, "reload");
		check(TickEngineConfig.SPREAD_EXCLUDE.targets().containsAll(java.util.Set.of("minecraft:villager", "somemod:*")),
			"exclusions lost after a reload: " + TickEngineConfig.SPREAD_EXCLUDE.targets());
		check(run(helper, "/tickengine spread exclude remove minecraft:villager").result() == 1, "remove");
		check(run(helper, "/tickengine spread exclude remove minecraft:villager").result() == 0, "removing twice succeeded");
		check(run(helper, "/tickengine config reload").result() == 1, "reload");
		check(TickEngineConfig.SPREAD_EXCLUDE.targets().equals(java.util.Set.of("somemod:*")),
			"after remove and reload: " + TickEngineConfig.SPREAD_EXCLUDE.targets());
		configure(helper);
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "spread_loaded_excluded", timeoutTicks = 260)
	public static void confirmingSkipsExcludedEntitiesAlreadyLoaded(GameTestHelper helper) {
		configure(helper);
		check(run(helper, "/tickengine spread exclude add minecraft:cow").result() == 1, "exclude add");
		List<Cow> cows = cowsSpawnedTogether(helper);
		List<Pig> pigs = pigsSpawnedTogether(helper);
		helper.runAfterDelay(EntityThrottle.SPREAD_AT_TICK + 20, () -> {
			int[] cowsBefore = cows.stream().mapToInt(cow -> cow.tickCount).toArray();
			int[] pigsBefore = pigs.stream().mapToInt(pig -> pig.tickCount).toArray();
			run(helper, "/tickengine spread enable");
			check(run(helper, "/tickengine spread confirm I understand the risks").result() == 1, "confirm");
			for (int i = 0; i < COWS; i++) {
				check(cows.get(i).tickCount == cowsBefore[i], "excluded cow " + cows.get(i).getId() + " was moved");
				Pig pig = pigs.get(i);
				if (pigsBefore[i] > EntityThrottle.SPREAD_AT_TICK) {
					check(pig.tickCount - pigsBefore[i] == EntityThrottle.spreadOffset(pig), "pig " + pig.getId()
						+ " moved by " + (pig.tickCount - pigsBefore[i]) + ", expected " + EntityThrottle.spreadOffset(pig));
				}
			}
			configure(helper);
			helper.succeed();
		});
	}
}
