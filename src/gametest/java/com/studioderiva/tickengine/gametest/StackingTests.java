package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.run;
import static com.studioderiva.tickengine.gametest.TestSupport.spawnPlain;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.stacking.EntityStacker;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Entity stacking: merging, dying one at a time, /kill, using a stack, the mob cap and unstacking. */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StackingTests {
	private StackingTests() {
	}

	private static <E extends Entity> List<E> inArea(GameTestHelper helper, EntityType<E> type) {
		AABB area = new AABB(helper.absolutePos(new BlockPos(0, 0, 0)),
			helper.absolutePos(new BlockPos(16, 6, 16)));
		return helper.getLevel().getEntities(type, area, Entity::isAlive);
	}

	private static int total(List<? extends Entity> entities) {
		return entities.stream().mapToInt(EntityStacker::count).sum();
	}

	@GameTest(template = "floor", batch = "stacking_merge", timeoutTicks = 300)
	public static void cowsStackDieOneAtATimeAndKillRemovesTheRest(GameTestHelper helper) {
		configure(helper, "stacking.mode", "always");
		for (int i = 0; i < 3; i++) {
			spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		}
		helper.runAfterDelay(60, () -> {
			List<Cow> cows = inArea(helper, EntityType.COW);
			check(cows.size() == 1 && EntityStacker.count(cows.get(0)) == 3, "expected one stack of 3, got " + cows.size()
				+ " cows holding " + total(cows));
			Cow stack = cows.get(0);
			check(stack.getCustomName() != null && stack.getCustomName().getString().equals("Cow x3"),
				"stack name: " + stack.getCustomName());
			check(stack.getTags().contains(EntityStacker.TAG_PREFIX + "3"), "tags: " + stack.getTags());
			stack.hurt(helper.getLevel().damageSources().generic(), 1000);
			helper.runAfterDelay(2, () -> {
				List<Cow> after = inArea(helper, EntityType.COW);
				check(after.size() == 1 && EntityStacker.count(after.get(0)) == 2, "after one death expected one stack of 2, got "
					+ after.size() + " cows holding " + total(after));
				check(after.get(0).getHealth() == after.get(0).getMaxHealth(), "the rest should have full health");
				after.get(0).kill();
				helper.runAfterDelay(2, () -> {
					check(inArea(helper, EntityType.COW).isEmpty(), "kill should remove the whole stack");
					helper.succeed();
				});
			});
		});
	}

	@GameTest(template = "floor", batch = "stacking_rules", timeoutTicks = 300)
	public static void differentDataBabiesAndNamedMobsNeverStack(GameTestHelper helper) {
		configure(helper, "stacking.mode", "always");
		Sheep white = spawnPlain(helper, EntityType.SHEEP, 4.5, 2, 4.5);
		Sheep red = spawnPlain(helper, EntityType.SHEEP, 4.5, 2, 4.5);
		red.setColor(DyeColor.RED);
		Cow baby = spawnPlain(helper, EntityType.COW, 12.5, 2, 12.5);
		baby.setAge(-24000);
		Cow named = spawnPlain(helper, EntityType.COW, 12.5, 2, 12.5);
		named.setCustomName(Component.literal("Bessie"));
		spawnPlain(helper, EntityType.COW, 12.5, 2, 12.5);
		helper.runAfterDelay(60, () -> {
			check(inArea(helper, EntityType.SHEEP).size() == 2, "a red and a white sheep stacked");
			List<Cow> cows = inArea(helper, EntityType.COW);
			check(cows.size() == 3 && cows.stream().noneMatch(EntityStacker::isStack), "a baby or named cow stacked: "
				+ cows.size() + " cows");
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "stacking_off", timeoutTicks = 200)
	public static void nothingStacksWhileTheModeIsOff(GameTestHelper helper) {
		configure(helper);
		for (int i = 0; i < 3; i++) {
			spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		}
		helper.runAfterDelay(60, () -> {
			check(inArea(helper, EntityType.COW).size() == 3, "cows stacked with stacking.mode off");
			helper.succeed();
		});
	}

	/** Shearing goes through Forge's IForgeShearable from the shears item, not Mob.interact. */
	@GameTest(template = "floor", batch = "stacking_shear", timeoutTicks = 300)
	public static void shearingAStackShearsOneSheep(GameTestHelper helper) {
		configure(helper, "stacking.mode", "always");
		for (int i = 0; i < 3; i++) {
			spawnPlain(helper, EntityType.SHEEP, 8.5, 2, 8.5);
		}
		helper.runAfterDelay(60, () -> {
			List<Sheep> sheep = inArea(helper, EntityType.SHEEP);
			check(sheep.size() == 1 && EntityStacker.count(sheep.get(0)) == 3, "expected one stack of 3 sheep");
			ServerPlayer player = TestSupport.playerAt(helper, sheep.get(0).getX(), sheep.get(0).getZ());
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
			player.interactOn(sheep.get(0), InteractionHand.MAIN_HAND);
			helper.runAfterDelay(2, () -> {
				List<Sheep> after = inArea(helper, EntityType.SHEEP);
				long sheared = after.stream().filter(Sheep::isSheared).mapToInt(EntityStacker::count).sum();
				long woolly = after.stream().filter(s -> !s.isSheared()).mapToInt(EntityStacker::count).sum();
				check(sheared == 1 && woolly == 2, "expected 1 sheared and 2 woolly sheep, got " + sheared + " and " + woolly);
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "stacking_feed", timeoutTicks = 300)
	public static void feedingAStackFeedsOneCow(GameTestHelper helper) {
		configure(helper, "stacking.mode", "always");
		for (int i = 0; i < 3; i++) {
			spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		}
		helper.runAfterDelay(60, () -> {
			List<Cow> cows = inArea(helper, EntityType.COW);
			check(cows.size() == 1 && EntityStacker.count(cows.get(0)) == 3, "expected one stack of 3 cows");
			ServerPlayer player = TestSupport.playerAt(helper, cows.get(0).getX(), cows.get(0).getZ());
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
			player.interactOn(cows.get(0), InteractionHand.MAIN_HAND);
			List<Cow> after = inArea(helper, EntityType.COW);
			long inLove = after.stream().filter(Cow::isInLove).mapToInt(EntityStacker::count).sum();
			check(after.size() == 2 && inLove == 1 && total(after) == 3, "expected one cow in love apart from a stack of 2, got "
				+ after.size() + " cows, " + inLove + " in love");
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "stacking_cap", timeoutTicks = 100)
	public static void theMobCapCountsEveryMobInAStack(GameTestHelper helper) {
		configure(helper);
		Cow stack = spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		EntityStacker.setCount(stack, 5);
		Cow single = spawnPlain(helper, EntityType.COW, 4.5, 2, 4.5);
		int counted = 0;
		for (Entity ignored : EntityStacker.expandStacks(List.of(stack, single))) {
			counted++;
		}
		check(counted == 6, "natural spawning should count 6 mobs, counted " + counted);
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "stacking_unstack", timeoutTicks = 100)
	public static void unstackSplitsEveryStack(GameTestHelper helper) {
		configure(helper);
		Mob stack = spawnPlain(helper, EntityType.COW, 8.5, 2, 8.5);
		EntityStacker.setCount(stack, 4);
		TestSupport.CommandResult result = run(helper, "/tickengine stacking unstack");
		helper.runAfterDelay(2, () -> {
			List<Cow> cows = inArea(helper, EntityType.COW);
			check(cows.size() == 4 && cows.stream().noneMatch(EntityStacker::isStack) && cows.stream().allMatch(c -> c.getCustomName() == null),
				"expected 4 plain cows, got " + cows.size() + ": " + result.text());
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "stacking_command", timeoutTicks = 100)
	public static void turningStackingOnNeedsTheConfirmation(GameTestHelper helper) {
		configure(helper);
		TestSupport.CommandResult set = run(helper, "/tickengine config set stacking.mode always");
		check(TickEngineConfig.stackingMode.equals("off"), "config set turned stacking on: " + set.text());
		run(helper, "/tickengine stacking mode always");
		check(TickEngineConfig.stackingMode.equals("off"), "mode changed before the confirmation");
		TestSupport.CommandResult confirm = run(helper, "/tickengine stacking confirm I understand the risks");
		check(TickEngineConfig.stackingMode.equals("always"), "confirm did not set the mode: "
			+ confirm.text());
		run(helper, "/tickengine stacking mode off");
		check(TickEngineConfig.stackingMode.equals("off"), "mode off needs no confirmation");
		helper.succeed();
	}
}
