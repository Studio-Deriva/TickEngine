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
import net.minecraft.world.entity.animal.Cow;
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
	private static int[] watchJumps(GameTestHelper helper, List<Cow> cows) {
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
}
