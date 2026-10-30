package com.studioderiva.tickengine.throttle;

import com.studioderiva.tickengine.compat.ServerCoreCompat;
import com.studioderiva.tickengine.config.ThrottleRules;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.mixin.EntityInvoker;
import com.studioderiva.tickengine.mixin.ExperienceOrbAccessor;
import com.studioderiva.tickengine.mixin.ItemEntityAccessor;
import com.studioderiva.tickengine.mixin.LivingEntityAccessor;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
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
import net.minecraftforge.common.Tags;

/**
 * Decides how much of an entity's tick runs.
 *
 * <ul>
 *   <li>{@link Mode#FULL}: normal tick.</li>
 *   <li>{@link Mode#THROTTLED_FULL}: normal tick of a throttled entity on its turn. Mobs run a
 *   full goal selection, see {@link #isThrottledRun}.</li>
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
	public enum Mode { FULL, THROTTLED_FULL, AI_SKIPPED, MINIMAL }

	private static final byte FAR = Byte.MAX_VALUE;
	/** With entities.spreadTicks, an entity's tick counter is moved once when it reaches this value (5 s). */
	public static final int SPREAD_AT_TICK = 100;
	/** Counters are moved by 0 to SPREAD_RANGE - 1, which spreads every interval that divides 20. */
	public static final int SPREAD_RANGE = 20;
	/** Chebyshev chunk distance to the nearest player, for chunks up to aiRadiusChunks. */
	private static final Long2ByteOpenHashMap playerDistance = new Long2ByteOpenHashMap();
	private static ServerLevel preparedLevel;
	private static long preparedGameTime = Long.MIN_VALUE;
	private static LongSet forcedChunks = LongSet.of();
	private static boolean active;

	private static final Map<ResourceKey<Level>, Map<EntityType<?>, ThrottleRules.EntityIntervals>> intervals = new HashMap<>();
	/** Types in entities.spreadExclude, by type. */
	private static final Map<EntityType<?>, Boolean> spreadExcluded = new HashMap<>();
	private static int intervalsVersion = -1;

	/** The entity whose tick is currently running with a throttled mode, if any. */
	private static Entity currentEntity;
	private static Mode currentMode = Mode.FULL;

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
		forcedChunks = level.getForcedChunks();
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
		// Only while entity throttling runs: without it, entities never share a throttled turn.
		if (TickEngineConfig.spreadEntityTicks && entity.tickCount == SPREAD_AT_TICK && canSpread(entity, level)) {
			spread(entity);
		}
		long chunk = entity.chunkPosition().toLong();
		int distance = playerDistance.get(chunk);
		if (distance <= AutoTuner.values().entityFullRadius() || isExempt(entity)) {
			fullNow++;
			return Mode.FULL;
		}
		ThrottleRules.EntityIntervals rule = intervals(level, entity.getType());
		boolean aiZone = distance <= AutoTuner.values().entityAiRadius();
		int interval = aiZone ? rule.ai() : rule.tick();
		if (interval > 1 && forcedChunks.contains(chunk)) {
			interval = Math.min(interval, EmergencyMode.adjustInterval(AutoTuner.values().entityForcedMax()));
		}
		if (interval <= 1) {
			fullNow++;
			return Mode.FULL;
		}
		if (entity.tickCount % interval == 0) {
			fullNow++;
			return Mode.THROTTLED_FULL;
		}
		if (aiZone) {
			if (!(entity instanceof Mob)) {
				// Only mobs have AI to skip: anything else ticks normally in the AI zone.
				fullNow++;
				return Mode.FULL;
			}
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
		if (TickEngineConfig.excludeBosses && entity.getType().is(Tags.EntityTypes.BOSSES)) {
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

	public static boolean inCombat(LivingEntity living) {
		// lastHurtByMob is only set when the damage comes from a living entity (directly or
		// with a projectile), so fire, cactus and other environmental damage do not count.
		if (living.getLastHurtByMob() != null
			&& living.tickCount - living.getLastHurtByMobTimestamp() < TickEngineConfig.combatSeconds * 20) {
			return true;
		}
		return living instanceof Mob mob && mob.getTarget() != null;
	}

	/** Drops what was derived from the config when it changed. */
	private static void checkCaches() {
		if (intervalsVersion != TickEngineConfig.version) {
			intervals.clear();
			spreadExcluded.clear();
			intervalsVersion = TickEngineConfig.version;
		}
	}

	private static ThrottleRules.EntityIntervals intervals(ServerLevel level, EntityType<?> type) {
		checkCaches();
		Map<EntityType<?>, ThrottleRules.EntityIntervals> cache = intervals.computeIfAbsent(level.dimension(),
			key -> new HashMap<>());
		ThrottleRules.EntityIntervals rule = cache.get(type);
		if (rule == null) {
			ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
			// Unregistered types cannot be matched by rules: leave them alone.
			rule = id == null ? new ThrottleRules.EntityIntervals(1, 1) : TickEngineConfig.ENTITY_RULES.resolve(id,
				level.dimension().location(), TickEngineConfig.entityDimensionRulesEnabled,
				new ThrottleRules.EntityIntervals(AutoTuner.values().entityAiInterval(), AutoTuner.values().entityTickInterval()));
			// The cache is rebuilt when emergency mode starts or ends (it bumps the config version).
			rule = new ThrottleRules.EntityIntervals(EmergencyMode.adjustInterval(rule.ai()), EmergencyMode.adjustInterval(rule.tick()));
			cache.put(type, rule);
		}
		return rule;
	}

	/**
	 * Whether tick spreading may move this entity's counter: not when its type is in
	 * entities.spreadExclude, and not when its type is never throttled, since it then never has
	 * a throttled turn to spread.
	 */
	public static boolean canSpread(Entity entity, ServerLevel level) {
		if (entity instanceof Player) {
			return false;
		}
		EntityType<?> type = entity.getType();
		checkCaches();
		if (spreadExcluded.computeIfAbsent(type, key -> {
			ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(key);
			return id == null || TickEngineConfig.SPREAD_EXCLUDE.matches(id);
		})) {
			return false;
		}
		if (TickEngineConfig.excludeProjectiles && entity instanceof Projectile
			|| TickEngineConfig.excludeBosses && type.is(Tags.EntityTypes.BOSSES)) {
			return false;
		}
		ThrottleRules.EntityIntervals rule = intervals(level, type);
		return rule.ai() > 1 || rule.tick() > 1;
	}

	/** Marks the entity whose tick is about to run with the given mode; null to clear. */
	public static void setCurrent(Entity entity, Mode mode) {
		currentEntity = entity;
		currentMode = entity == null ? Mode.FULL : mode;
	}

	public static boolean isAiSkipped(Entity entity) {
		return currentEntity == entity && currentMode == Mode.AI_SKIPPED;
	}

	/**
	 * True while a throttled mob runs its AI on its turn. Vanilla alternates a full goal
	 * selection and a running-goals-only update on the parity of the server tick count plus the
	 * entity id; since throttled turns fall on multiples of the interval of the entity's own
	 * tickCount, which differs from the server tick count by a fixed offset, an even interval
	 * would leave about half of the mobs on the partial update forever, so they would never start
	 * or stop a goal.
	 */
	public static boolean isThrottledRun(Entity entity) {
		return currentEntity == entity && currentMode == Mode.THROTTLED_FULL;
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

	/**
	 * Moves the tick counter forward by a fixed amount per entity. Vanilla starts the counter at 0
	 * whenever an entity is loaded, so its value only matters modulo the periods logic checks;
	 * this is the same as having been loaded a few ticks earlier. The amount comes from the
	 * entity id, so entities loaded together end up on different throttled turns.
	 */
	private static void spread(Entity entity) {
		entity.tickCount += spreadOffset(entity);
	}

	public static int spreadOffset(Entity entity) {
		return Math.floorMod(entity.getId() * 0x9E3779B9, SPREAD_RANGE);
	}

	/**
	 * Applies the counter move to entities at or past {@link #SPREAD_AT_TICK} when spreading is
	 * turned on, so they do not wait for their next load (those exactly at it already had their
	 * check this tick). Returns how many were moved.
	 */
	public static int spreadLoadedEntities(MinecraftServer server) {
		if (!isActive()) {
			return 0;
		}
		int moved = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.tickCount >= SPREAD_AT_TICK && canSpread(entity, level)) {
					spread(entity);
					moved++;
				}
			}
		}
		return moved;
	}

	public static long minimalLastSecond() {
		return minimalLastSecond;
	}

	public static void reset() {
		preparedLevel = null;
		setCurrent(null, Mode.FULL);
		playerDistance.clear();
		forcedChunks = LongSet.of();
		intervals.clear();
		spreadExcluded.clear();
		fullNow = aiSkippedNow = minimalNow = statsTicks = 0;
		fullLastSecond = aiSkippedLastSecond = minimalLastSecond = 0;
	}
}
