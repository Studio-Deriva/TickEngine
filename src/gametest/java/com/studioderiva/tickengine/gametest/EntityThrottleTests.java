package com.studioderiva.tickengine.gametest;

import static com.studioderiva.tickengine.gametest.TestSupport.check;
import static com.studioderiva.tickengine.gametest.TestSupport.checkBetween;
import static com.studioderiva.tickengine.gametest.TestSupport.configure;
import static com.studioderiva.tickengine.gametest.TestSupport.create;
import static com.studioderiva.tickengine.gametest.TestSupport.spawnPlain;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.mixin.ExperienceOrbAccessor;
import com.studioderiva.tickengine.mixin.ItemEntityAccessor;
import com.studioderiva.tickengine.mixin.LivingEntityAccessor;
import com.studioderiva.tickengine.throttle.EntityThrottle;
import com.studioderiva.tickengine.throttle.EntityThrottle.Mode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Entity throttling: the decision (exemptions, zones, rules) and its effect on real entities
 * (minimal ticks advance timers, throttled mobs still select goals). Exercises ServerLevelMixin,
 * MobMixin and the accessors.
 */
@GameTestHolder(TickEngine.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EntityThrottleTests {
	private static final int WINDOW = 40;

	private EntityThrottleTests() {
	}

	/** Forces EntityThrottle to prepare again, so config changes made this tick are picked up. */
	private static Mode decide(Entity entity) {
		EntityThrottle.onTickEnd();
		return EntityThrottle.decide(entity);
	}

	private static Cow idleCow(GameTestHelper helper, int tickCount) {
		Cow cow = create(helper, EntityType.COW, 8.5, 2, 8.5);
		cow.setOnGround(true);
		cow.tickCount = tickCount;
		return cow;
	}

	// --- decision ---

	@GameTest(template = "floor", batch = "entity_decision", timeoutTicks = 40)
	public static void farEntityAlternatesFullAndMinimal(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		check(decide(idleCow(helper, 4)) == Mode.THROTTLED_FULL, "tickCount 4 should be the throttled turn");
		check(decide(idleCow(helper, 5)) == Mode.MINIMAL, "tickCount 5 should be minimal");
		check(decide(create(helper, EntityType.ITEM, 8.5, 2, 8.5)) != Mode.AI_SKIPPED, "items have no AI to skip");
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "entity_disabled", timeoutTicks = 40)
	public static void disabledMeansFull(GameTestHelper helper) {
		configure(helper, "entities.enabled", "false");
		check(decide(idleCow(helper, 5)) == Mode.FULL, "throttling off should tick fully");
		check(EntityThrottle.isActive() == false, "isActive with entities.enabled false");
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "entity_exemptions", timeoutTicks = 40)
	public static void exemptionsTickFully(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");

		Arrow arrow = create(helper, EntityType.ARROW, 8.5, 2, 8.5);
		arrow.tickCount = 5;
		check(decide(arrow) == Mode.FULL, "projectiles are exempt");

		Entity wither = create(helper, EntityType.WITHER, 8.5, 2, 8.5);
		wither.tickCount = 5;
		check(decide(wither) == Mode.FULL, "bosses (forge:bosses) are exempt");

		Cow baby = idleCow(helper, 5);
		baby.setAge(-24000);
		check(decide(baby) == Mode.FULL, "babies are woken");

		Cow inLove = idleCow(helper, 5);
		inLove.setInLove(null);
		check(decide(inLove) == Mode.FULL, "animals in love are woken");

		Cow hurt = idleCow(helper, 5);
		hurt.setLastHurtByMob(create(helper, EntityType.ZOMBIE, 9.5, 2, 9.5));
		check(decide(hurt) == Mode.FULL, "mobs hit by a mob are in combat");

		Zombie hunter = create(helper, EntityType.ZOMBIE, 8.5, 2, 8.5);
		hunter.setOnGround(true);
		hunter.tickCount = 5;
		hunter.setTarget(create(helper, EntityType.COW, 9.5, 2, 9.5));
		check(decide(hunter) == Mode.FULL, "mobs with a target are in combat");

		Cow falling = idleCow(helper, 5);
		falling.setOnGround(false);
		falling.setDeltaMovement(new Vec3(0, -0.5, 0));
		check(decide(falling) == Mode.FULL, "falling entities are woken");

		check(decide(idleCow(helper, 5)) == Mode.MINIMAL, "the control cow should be throttled");

		configure(helper, "entities.defaultTickInterval", "4", "entities.wakeNewEntities", "true", "entities.newEntitySeconds", "10");
		check(decide(idleCow(helper, 5)) == Mode.FULL, "new entities are woken");
		check(decide(idleCow(helper, 201)) == Mode.MINIMAL, "entities older than newEntitySeconds are not");

		configure(helper, "entities.defaultTickInterval", "4", "entities.excludeProjectiles", "false", "entities.excludeBosses", "false");
		check(decide(arrow) == Mode.MINIMAL, "projectiles throttled when not excluded");
		check(decide(wither) != Mode.FULL, "bosses throttled when not excluded");
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "entity_rules", timeoutTicks = 40)
	public static void rulesAndDefaultRules(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		Entity guardian = create(helper, EntityType.ELDER_GUARDIAN, 8.5, 2, 8.5);
		guardian.tickCount = 5;
		check(decide(guardian) == Mode.FULL, "elder guardian is never throttled by default");
		Entity warden = create(helper, EntityType.WARDEN, 8.5, 2, 8.5);
		warden.tickCount = 5;
		check(decide(warden) == Mode.FULL, "warden is never throttled by default");

		check(TestSupport.run(helper, "/tickengine rules entities set 1 1 minecraft:cow").result() == 1, "cow rule not set");
		Mode withRule = decide(idleCow(helper, 5));
		check(TestSupport.run(helper, "/tickengine rules entities set 2 10 minecraft:*").result() == 1, "mod rule not set");
		Mode typeBeatsMod = decide(idleCow(helper, 5));
		TestSupport.run(helper, "/tickengine rules entities remove minecraft:cow");
		Mode modRule = decide(idleCow(helper, 10));
		Mode modRuleOff = decide(idleCow(helper, 4));
		TestSupport.run(helper, "/tickengine rules entities remove minecraft:*");
		check(withRule == Mode.FULL, "rule 1/1 should tick fully, was " + withRule);
		check(typeBeatsMod == Mode.FULL, "type rule should beat mod rule, was " + typeBeatsMod);
		check(modRule == Mode.THROTTLED_FULL, "mod rule interval 10 at tickCount 10, was " + modRule);
		check(modRuleOff == Mode.MINIMAL, "mod rule interval 10 at tickCount 4, was " + modRuleOff);
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "entity_forced", timeoutTicks = 40)
	public static void forcedChunksAreCapped(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "10", "entities.forcedChunkMaxInterval", "2");
		BlockPos absolute = helper.absolutePos(new BlockPos(8, 2, 8));
		helper.getLevel().setChunkForced(absolute.getX() >> 4, absolute.getZ() >> 4, true);
		check(decide(idleCow(helper, 2)) == Mode.THROTTLED_FULL, "interval capped at 2 in a forced chunk");
		check(decide(idleCow(helper, 3)) == Mode.MINIMAL, "odd tick in a forced chunk");
		helper.succeed();
	}

	@GameTest(template = "floor", batch = "entity_zones", timeoutTicks = 40)
	public static void zonesAroundPlayers(GameTestHelper helper) {
		configure(helper, "entities.fullRadiusChunks", "1", "entities.aiRadiusChunks", "3",
			"entities.defaultAiInterval", "4", "entities.defaultTickInterval", "4");
		BlockPos cowPos = helper.absolutePos(new BlockPos(8, 2, 8));
		TestSupport.playerAt(helper, cowPos.getX() + 32, cowPos.getZ());
		check(decide(idleCow(helper, 5)) == Mode.AI_SKIPPED, "2 chunks away: AI zone");
		check(decide(idleCow(helper, 8)) == Mode.THROTTLED_FULL, "AI zone, on the AI turn");
		ItemEntity item = create(helper, EntityType.ITEM, 8.5, 2, 8.5);
		item.tickCount = 5;
		check(decide(item) == Mode.FULL, "non-mobs tick normally in the AI zone");
		check(decide(TestSupport.playerAt(helper, cowPos.getX(), cowPos.getZ())) == Mode.FULL, "players are never throttled");
		check(decide(idleCow(helper, 5)) == Mode.FULL, "same chunk as a player: full");
		helper.succeed();
	}

	// --- effect on real entities ---

	@GameTest(template = "floor", batch = "entity_item_age", timeoutTicks = 200)
	public static void itemsAgeEveryTickWhileThrottled(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		ItemEntity item = spawnPlain(helper, EntityType.ITEM, 8.5, 2, 8.5);
		item.setItem(new ItemStack(Items.STONE));
		ItemEntityAccessor accessor = (ItemEntityAccessor) item;
		helper.runAfterDelay(20, () -> {
			int before = accessor.tickengine$getAge();
			helper.runAfterDelay(WINDOW, () -> {
				checkBetween(accessor.tickengine$getAge() - before, WINDOW, WINDOW, "item age over " + WINDOW + " ticks");
				check(EntityThrottle.minimalLastSecond() > 0, "no minimal ticks counted in the last second");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "entity_despawn", timeoutTicks = 200)
	public static void itemsAndOrbsStillDespawn(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "20");
		ItemEntity item = spawnPlain(helper, EntityType.ITEM, 6.5, 2, 6.5);
		item.setItem(new ItemStack(Items.STONE));
		ExperienceOrb orb = new ExperienceOrb(helper.getLevel(), 0, 0, 0, 5);
		Vec3 pos = helper.absoluteVec(new Vec3(10.5, 2, 10.5));
		orb.moveTo(pos.x, pos.y, pos.z);
		helper.getLevel().addFreshEntity(orb);
		helper.runAfterDelay(20, () -> {
			((ItemEntityAccessor) item).tickengine$setAge(5990);
			((ExperienceOrbAccessor) orb).tickengine$setAge(5990);
			helper.runAfterDelay(15, () -> {
				check(item.isRemoved(), "item not despawned after reaching age 6000");
				check(orb.isRemoved(), "orb not despawned after reaching age 6000");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "entity_never_despawn", timeoutTicks = 200)
	public static void unlimitedLifetimeItemsStay(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "20");
		ItemEntity item = spawnPlain(helper, EntityType.ITEM, 8.5, 2, 8.5);
		item.setItem(new ItemStack(Items.STONE));
		item.setUnlimitedLifetime();
		helper.runAfterDelay(40, () -> {
			check(!item.isRemoved(), "item with unlimited lifetime was removed");
			check(((ItemEntityAccessor) item).tickengine$getAge() == -32768, "unlimited lifetime age changed");
			helper.succeed();
		});
	}

	@GameTest(template = "floor", batch = "entity_timers", timeoutTicks = 200)
	public static void minimalTickAdvancesMobTimers(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		// Two cows: burning hurts, and getting hurt resets the idle time.
		Cow idle = spawnPlain(helper, EntityType.COW, 4.5, 2, 4.5);
		Cow burning = spawnPlain(helper, EntityType.COW, 11.5, 2, 11.5);
		helper.runAfterDelay(20, () -> {
			LivingEntityAccessor accessor = (LivingEntityAccessor) idle;
			int idleBefore = accessor.tickengine$getNoActionTime();
			int tickBefore = idle.tickCount;
			burning.setPortalCooldown(200);
			burning.setRemainingFireTicks(200);
			helper.runAfterDelay(WINDOW, () -> {
				checkBetween(accessor.tickengine$getNoActionTime() - idleBefore, WINDOW - 1, WINDOW + 1, "idle time gained (before "
					+ idleBefore + ", ticks " + (idle.tickCount - tickBefore) + ", persistent " + idle.isPersistenceRequired()
					+ ", removed " + idle.isRemoved() + ", players " + helper.getLevel().players() + ", custom persistence "
					+ idle.requiresCustomPersistence() + ")");
				checkBetween(200 - burning.getPortalCooldown(), WINDOW - 1, WINDOW + 1, "portal cooldown spent");
				checkBetween(200 - burning.getRemainingFireTicks(), WINDOW - 1, WINDOW + 1, "fire ticks spent");
				helper.succeed();
			});
		});
	}

	/**
	 * The goal fix: on its throttled turn every mob runs a full goal selection, whatever the parity
	 * of server tick + id (vanilla 1.20.1 alternates full and running-only updates on it). Without
	 * the fix about half of the mobs would never be asked to start a goal.
	 */
	@GameTest(template = "floor", batch = "entity_goals_far", timeoutTicks = 200)
	public static void throttledMobsStillSelectGoals(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		checkGoalChecks(helper, 7, 13, "far zone");
	}

	@GameTest(template = "floor", batch = "entity_goals_ai", timeoutTicks = 200)
	public static void aiZoneMobsStillSelectGoals(GameTestHelper helper) {
		configure(helper, "entities.fullRadiusChunks", "0", "entities.aiRadiusChunks", "8", "entities.defaultAiInterval", "4");
		BlockPos center = helper.absolutePos(new BlockPos(8, 2, 8));
		TestSupport.playerAt(helper, center.getX() + 48, center.getZ());
		checkGoalChecks(helper, 7, 13, "AI zone");
	}

	@GameTest(template = "floor", batch = "entity_goals_off", timeoutTicks = 200)
	public static void unthrottledMobsBaseline(GameTestHelper helper) {
		configure(helper, "entities.enabled", "false");
		checkGoalChecks(helper, 15, 25, "throttling off (vanilla alternates every tick)");
	}

	private static void checkGoalChecks(GameTestHelper helper, int min, int max, String zone) {
		List<int[]> counters = new ArrayList<>();
		List<Cow> cows = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			Cow cow = spawnPlain(helper, EntityType.COW, 3.5 + i * 2, 2, 12.5);
			cows.add(cow);
			counters.add(TestSupport.countGoalChecks(cow));
		}
		helper.runAfterDelay(10, () -> {
			int[] before = counters.stream().mapToInt(counter -> counter[0]).toArray();
			helper.runAfterDelay(WINDOW, () -> {
				boolean evenId = false;
				boolean oddId = false;
				for (int i = 0; i < counters.size(); i++) {
					checkBetween(counters.get(i)[0] - before[i], min, max, "goal checks of cow " + cows.get(i).getId() + " in the " + zone);
					evenId |= cows.get(i).getId() % 2 == 0;
					oddId |= cows.get(i).getId() % 2 != 0;
				}
				check(evenId && oddId, "test needs cows with both even and odd ids");
				helper.succeed();
			});
		});
	}

	@GameTest(template = "floor", batch = "entity_fluid", timeoutTicks = 100)
	public static void waterWakesLandMobsOnly(GameTestHelper helper) {
		configure(helper, "entities.defaultTickInterval", "4");
		for (int x = 2; x <= 5; x++) {
			for (int z = 2; z <= 5; z++) {
				helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
				helper.setBlock(new BlockPos(x, 3, z), Blocks.WATER);
			}
		}
		Cow cow = spawnPlain(helper, EntityType.COW, 3.5, 2, 3.5);
		Entity cod = spawnPlain(helper, EntityType.COD, 4.5, 2, 4.5);
		helper.runAfterDelay(5, () -> {
			check(cow.isInWater(), "cow should be in water");
			check(decide(cow) == Mode.FULL, "land mob in water should be woken");
			Mode codMode = decide(cod);
			check(codMode == Mode.MINIMAL || codMode == Mode.THROTTLED_FULL, "fish in water should stay throttled, was " + codMode);
			helper.succeed();
		});
	}
}
