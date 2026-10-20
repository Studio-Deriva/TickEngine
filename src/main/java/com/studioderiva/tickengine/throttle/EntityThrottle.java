package com.studioderiva.tickengine.throttle;

import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.ThrottleRules;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.mixin.EntityInvoker;
import com.studioderiva.tickengine.mixin.ExperienceOrbAccessor;
import com.studioderiva.tickengine.mixin.ItemEntityAccessor;
import com.studioderiva.tickengine.mixin.LivingEntityAccessor;
import com.studioderiva.tickengine.tuning.AutoTuner;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Decides how much of an entity's tick runs.
 *
 * <ul>
 *   <li>{@link Mode#FULL}: normal tick.</li>
 *   <li>{@link Mode#AI_SKIPPED}: normal tick, but the AI parts of {@code Mob.serverAiStep}
 *   (sensing, goals, navigation, brain) are skipped this tick.</li>
 *   <li>{@link Mode#MINIMAL}: the tick is replaced by {@link #minimalTick}, which only
 *   advances timers.</li>
 * </ul>
 *
 * <p>Throttled ticks are aligned to each entity's own {@code tickCount}, because vanilla and
 * mods often run logic on {@code tickCount % N} (item merging every 40 ticks, for example).
 * This also spreads the work of different entities over different ticks. Server thread only.
 */
public final class EntityThrottle {
	public enum Mode { FULL, AI_SKIPPED, MINIMAL }

	private static final byte FAR = Byte.MAX_VALUE;
	/** Chebyshev chunk distance to the nearest player, for chunks up to aiRadiusChunks. */
	private static final Long2ByteOpenHashMap playerDistance = new Long2ByteOpenHashMap();
	private static ServerLevel preparedLevel;
	private static long preparedGameTime = Long.MIN_VALUE;
	private static LongSet forcedChunks = LongSet.of();
	private static boolean active;

	private static final Map<ResourceKey<Level>, Map<EntityType<?>, ThrottleRules.EntityIntervals>> intervals = new HashMap<>();
	private static int intervalsVersion = -1;

	/** The entity whose AI is skipped in the tick currently running, if any. */
	private static Entity aiSkippedEntity;

	// Statistics for the last full second (20 game ticks), shown by /tickengine status.
	private static long fullNow;
	private static long aiSkippedNow;
	private static long minimalNow;
	private static long statsTicks;
	private static long fullLastSecond;
	private static long aiSkippedLastSecond;
	private static long minimalLastSecond;

	static {
		playerDistance.defaultReturnValue(FAR);
	}

	private EntityThrottle() {
	}

	/** True when entity throttling is enabled and not suspended because of ServerCore. */
	public static boolean isActive() {
		return TickEngineConfig.entityThrottle
			&& !(TickEngineConfig.disableEntityThrottleWithServerCore && ServerCoreCompat.activationRangeEnabled());
	}

	private static void prepare(ServerLevel level) {
		preparedLevel = level;
		preparedGameTime = level.getGameTime();
		active = isActive();
		if (!active) {
			return;
		}
		if (intervalsVersion != TickEngineConfig.version) {
			intervals.clear();
			intervalsVersion = TickEngineConfig.version;
		}
		playerDistance.clear();
		int radius = Math.max(AutoTuner.values().entityFullRadius(), AutoTuner.values().entityAiRadius());
		for (ServerPlayer player : level.players()) {
			ChunkPos center = player.chunkPosition();
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					long chunk = ChunkPos.asLong(center.x + dx, center.z + dz);
					byte distance = (byte) Math.max(Math.abs(dx), Math.abs(dz));
					if (distance < playerDistance.get(chunk)) {
						playerDistance.put(chunk, distance);
					}
				}
			}
		}
		forcedChunks = level.getForceLoadedChunks();
	}

	/** Decides the mode for a non-passenger entity that is about to tick. */
	public static Mode decide(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player) {
			return Mode.FULL;
		}
		if (level != preparedLevel || level.getGameTime() != preparedGameTime) {
			prepare(level);
		}
		if (!active) {
			return Mode.FULL;
		}
		long chunk = entity.chunkPosition().toLong();
		int distance = playerDistance.get(chunk);
		if (distance <= AutoTuner.values().entityFullRadius() || isExempt(entity)) {
			fullNow++;
			return Mode.FULL;
		}
		ThrottleRules.EntityIntervals rule = intervals(entity.getType());
		boolean aiZone = distance <= AutoTuner.values().entityAiRadius();
		int interval = aiZone ? rule.ai() : rule.tick();
		if (interval > 1 && forcedChunks.contains(chunk)) {
			interval = Math.min(interval, TickEngineConfig.entityForcedChunkMaxInterval);
		}
		if (interval <= 1 || entity.tickCount % interval == 0) {
			fullNow++;
			return Mode.FULL;
		}
		if (aiZone) {
			aiSkippedNow++;
			return Mode.AI_SKIPPED;
		}
		minimalNow++;
		return Mode.MINIMAL;
	}

	private static boolean isExempt(Entity entity) {
		if (TickEngineConfig.excludeProjectiles && entity instanceof Projectile) {
			return true;
		}
		if (TickEngineConfig.excludeVehicles && entity.isVehicle()) {
			return true;
		}
		if (TickEngineConfig.excludeBosses && entity.getType().is(ConventionalEntityTypeTags.BOSSES)) {
			return true;
		}
		if (TickEngineConfig.wakeNewEntities && entity.tickCount <= TickEngineConfig.newEntitySeconds * 20) {
			return true;
		}
		if (TickEngineConfig.wakeFallingOrInFluid && isFallingOrInFluid(entity)) {
			return true;
		}
		if (entity instanceof LivingEntity living) {
			if (TickEngineConfig.excludeCombat && inCombat(living)) {
				return true;
			}
			if (TickEngineConfig.wakeBreeding && (living instanceof Animal animal && animal.isInLove()
				|| living instanceof AgeableMob ageable && ageable.isBaby())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isFallingOrInFluid(Entity entity) {
		if (entity.isInLava()) {
			return true;
		}
		if (entity.isInWater()) {
			// Fish, squid, drowned, guardians... live in water: waking them there would mean
			// never throttling them at all.
			return !(entity instanceof LivingEntity living && living.canBreatheUnderwater());
		}
		// Moving down noticeably while airborne. Flying mobs hovering in place are not woken.
		return !entity.onGround() && !entity.isNoGravity() && entity.getDeltaMovement().y < -0.1;
	}

	private static boolean inCombat(LivingEntity living) {
		// lastHurtByMob is only set when the damage comes from a living entity (directly or
		// with a projectile), so fire, cactus and other environmental damage do not count.
		if (living.getLastHurtByMob() != null
			&& living.tickCount - living.getLastHurtByMobTimestamp() < TickEngineConfig.combatSeconds * 20) {
			return true;
		}
		return living instanceof Mob mob && mob.getTarget() != null;
	}

	private static ThrottleRules.EntityIntervals intervals(EntityType<?> type) {
		Map<EntityType<?>, ThrottleRules.EntityIntervals> cache = intervals.computeIfAbsent(preparedLevel.dimension(),
			key -> new HashMap<>());
		ThrottleRules.EntityIntervals rule = cache.get(type);
		if (rule == null) {
			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			// Unregistered types cannot be matched by rules: leave them alone.
			rule = id == null ? new ThrottleRules.EntityIntervals(1, 1) : TickEngineConfig.ENTITY_RULES.resolve(id,
				preparedLevel.dimension().identifier(), TickEngineConfig.entityDimensionRulesEnabled,
				new ThrottleRules.EntityIntervals(AutoTuner.values().entityAiInterval(), AutoTuner.values().entityTickInterval()));
			cache.put(type, rule);
		}
		return rule;
	}

	/** Marks the entity whose AI must be skipped while its tick runs; null to clear. */
	public static void setAiSkipped(Entity entity) {
		aiSkippedEntity = entity;
	}

	public static boolean isAiSkipped(Entity entity) {
		return aiSkippedEntity == entity;
	}

	/**
	 * Replaces a skipped tick. ServerLevel already advanced tickCount and checks despawning
	 * every tick on its own, so only the per-entity timers are advanced here.
	 */
	public static void minimalTick(Entity entity) {
		((EntityInvoker) entity).tickengine$processPortalCooldown();
		if (entity.getRemainingFireTicks() > 0 && !entity.fireImmune()) {
			entity.setRemainingFireTicks(entity.getRemainingFireTicks() - 1);
		}
		if (entity instanceof LivingEntity living) {
			// Random despawning of far mobs depends on how long they have been idle.
			LivingEntityAccessor accessor = (LivingEntityAccessor) living;
			accessor.tickengine$setNoActionTime(accessor.tickengine$getNoActionTime() + 1);
		} else if (entity instanceof ItemEntity item) {
			ItemEntityAccessor accessor = (ItemEntityAccessor) item;
			int age = accessor.tickengine$getAge();
			// -32768 marks items that never despawn.
			if (age != -32768) {
				accessor.tickengine$setAge(age + 1);
				if (age + 1 >= 6000) {
					item.discard();
				}
			}
		} else if (entity instanceof ExperienceOrb orb) {
			ExperienceOrbAccessor accessor = (ExperienceOrbAccessor) orb;
			accessor.tickengine$setAge(accessor.tickengine$getAge() + 1);
			if (accessor.tickengine$getAge() >= 6000) {
				orb.discard();
			}
		}
	}

	/** Called at the end of every server tick. */
	public static void onTickEnd() {
		preparedLevel = null;
		if (++statsTicks >= 20) {
			fullLastSecond = fullNow;
			aiSkippedLastSecond = aiSkippedNow;
			minimalLastSecond = minimalNow;
			fullNow = aiSkippedNow = minimalNow = statsTicks = 0;
		}
	}

	public static long fullLastSecond() {
		return fullLastSecond;
	}

	public static long aiSkippedLastSecond() {
		return aiSkippedLastSecond;
	}

	public static long minimalLastSecond() {
		return minimalLastSecond;
	}

	public static void reset() {
		preparedLevel = null;
		aiSkippedEntity = null;
		playerDistance.clear();
		forcedChunks = LongSet.of();
		intervals.clear();
		fullNow = aiSkippedNow = minimalNow = statsTicks = 0;
		fullLastSecond = aiSkippedLastSecond = minimalLastSecond = 0;
	}
}
