package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.burnTime;
import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.checkBetween;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.litFurnace;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.throttle.BlockEntityThrottle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Block entity throttling, measured on a real furnace: its burn time drops by one on every tick
 * that actually runs, so over 40 game ticks it shows how many ticks the throttle let through.
 * Exercises BoundTickingBlockEntityMixin and LevelMixin.
 */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockEntityThrottleTests {
	private static final BlockPos FURNACE = new BlockPos(8, 2, 8);
	private static final int WINDOW = 40;

	private BlockEntityThrottleTests() {
	}

	/** Lights the furnace, waits for it to settle, then checks the burn time lost over WINDOW ticks. */
	private static void measure(GameTestHelper helper, long min, long max, String what) {
		FurnaceBlockEntity furnace = litFurnace(helper, FURNACE);
		helper.runAfterDelay(10, () -> {
			int before = burnTime(furnace);
			check(before > 0, "furnace did not light");
			helper.runAfterDelay(WINDOW, () -> {
				checkBetween(before - burnTime(furnace), min, max, what);
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "be_off", timeoutTicks = 200)
	public static void furnaceRunsEveryTickWhenDisabled(GameTestHelper helper) {
		configure(helper, "blockEntities.enabled", "false");
		measure(helper, WINDOW - 1, WINDOW + 1, "burn ticks with throttling off");
	}

	@GameTest(template = "floor", batch = "be_interval", timeoutTicks = 200)
	public static void farFurnaceTicksOnceEveryInterval(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "4");
		measure(helper, WINDOW / 4 - 1, WINDOW / 4 + 1, "burn ticks at interval 4");
	}

	@GameTest(template = "floor", batch = "be_stagger", timeoutTicks = 200)
	public static void staggerKeepsTheSameRate(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "5", "blockEntities.stagger", "true");
		measure(helper, WINDOW / 5 - 1, WINDOW / 5 + 1, "burn ticks at interval 5 with stagger");
	}

	@GameTest(template = "floor", batch = "be_forced", timeoutTicks = 200)
	public static void forcedChunksAreCapped(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "10", "blockEntities.forcedChunkMaxInterval", "2");
		BlockPos absolute = helper.absolutePos(FURNACE);
		helper.getLevel().setChunkForced(absolute.getX() >> 4, absolute.getZ() >> 4, true);
		measure(helper, WINDOW / 2 - 1, WINDOW / 2 + 1, "burn ticks in a force-loaded chunk capped at 2");
	}

	@GameTest(template = "floor", batch = "be_rule", timeoutTicks = 200)
	public static void typeRuleOverridesTheDefault(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "10");
		check(TestSupport.run(helper, "/tickengine rules blockentities set 2 minecraft:furnace").result() == 1, "rule not set");
		FurnaceBlockEntity furnace = litFurnace(helper, FURNACE);
		helper.runAfterDelay(10, () -> {
			int before = burnTime(furnace);
			helper.runAfterDelay(WINDOW, () -> {
				int lost = before - burnTime(furnace);
				TestSupport.run(helper, "/tickengine rules blockentities remove minecraft:furnace");
				checkBetween(lost, WINDOW / 2 - 1, WINDOW / 2 + 1, "burn ticks with rule minecraft:furnace = 2");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "be_dimension_rule", timeoutTicks = 200)
	public static void dimensionRuleAppliesOnlyWhenEnabled(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "10", "blockEntities.dimensionRulesEnabled", "true");
		String dimension = helper.getLevel().dimension().location().toString();
		check(TestSupport.run(helper, "/tickengine rules blockentities set-in " + dimension + " 1 *").result() == 1,
			"dimension rule not set");
		FurnaceBlockEntity furnace = litFurnace(helper, FURNACE);
		helper.runAfterDelay(10, () -> {
			int before = burnTime(furnace);
			helper.runAfterDelay(WINDOW, () -> {
				int lost = before - burnTime(furnace);
				TestSupport.run(helper, "/tickengine rules blockentities remove-in " + dimension + " *");
				checkBetween(lost, WINDOW - 1, WINDOW + 1, "burn ticks with dimension rule * = 1");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "be_player", timeoutTicks = 200)
	public static void furnaceNearPlayerIsNotThrottled(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "10", "blockEntities.activeRadiusChunks", "1");
		BlockPos absolute = helper.absolutePos(FURNACE);
		TestSupport.playerAt(helper, absolute.getX() + 16, absolute.getZ());
		measure(helper, WINDOW - 1, WINDOW + 1, "burn ticks within the active radius of a player");
	}

	@GameTest(template = "floor", batch = "be_decision", timeoutTicks = 100)
	public static void decisionMatchesGameTime(GameTestHelper helper) {
		configure(helper, "blockEntities.defaultInterval", "4");
		FurnaceBlockEntity furnace = litFurnace(helper, FURNACE);
		int[] checked = {0};
		helper.onEachTick(() -> {
			BlockEntityThrottle.prepare(helper.getLevel());
			long time = helper.getLevel().getGameTime();
			boolean skip = BlockEntityThrottle.shouldSkip(furnace);
			check(skip == (time % 4 != 0), "shouldSkip at game time " + time + " was " + skip);
			if (++checked[0] == 12) {
				helper.succeed();
			}
		});
	}
}
