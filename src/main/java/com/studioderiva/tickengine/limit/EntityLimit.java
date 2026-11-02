package com.studioderiva.tickengine.limit;

import com.studioderiva.tickengine.compat.MinecraftCompat;
import com.studioderiva.tickengine.config.TickEngineConfig;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Entity limit per chunk: once a chunk holds the limit of a type, new mobs of that type stop
 * spawning or being born there. Nothing that is already there is removed, and a stack counts as one
 * mob. Spawn eggs, dispensers and commands are never stopped.
 *
 * <p>Counts are taken from the loaded entities the first time a chunk and type are asked about in a
 * tick, then raised for every spawn allowed in the same tick, so a spawn burst (a group of mobs, a
 * spawner placing four) cannot overshoot. Server thread only.
 */
public final class EntityLimit {
	/** Where a mob comes from. Each one can be left out of the limit in the config. */
	public enum Source {
		NATURAL("natural spawning"),
		SPAWNER("spawners"),
		TRIAL_SPAWNER("trial spawners"),
		BREEDING("breeding");

		public final String displayName;

		Source(String displayName) {
			this.displayName = displayName;
		}
	}

	/** One type in one chunk. */
	private record Key(ResourceKey<Level> dimension, int x, int z, EntityType<?> type) {
	}

	/** The last place a source was stopped, for '/tickengine limit'. */
	public record Blocked(ResourceKey<Level> dimension, BlockPos pos, Identifier type, int limit) {
	}

	private static final Map<Key, int[]> COUNTS = new HashMap<>();
	private static final Map<Source, long[]> BLOCKED = new EnumMap<>(Source.class);
	private static final Map<Source, Blocked> LAST_BLOCKED = new EnumMap<>(Source.class);
	private static int countsTick = Integer.MIN_VALUE;
	private static int cachedVersion = -1;

	private EntityLimit() {
	}

	/**
	 * Whether a mob of {@code type} may appear at {@code pos} from {@code source}. When it may, it is
	 * counted right away, so ask only when the mob is about to be added.
	 */
	public static boolean allows(ServerLevel level, EntityType<?> type, BlockPos pos, Source source) {
		if (!TickEngineConfig.entityLimitEnabled || !limits(source)) {
			return true;
		}
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if (TickEngineConfig.LIMIT_EXCLUDE.matches(id)) {
			return true;
		}
		int limit = TickEngineConfig.LIMIT_RULES.resolve(id, level.dimension().identifier(), true,
			TickEngineConfig.entityLimitPerChunk);
		int[] count = count(level, type, pos.getX() >> 4, pos.getZ() >> 4);
		if (count[0] >= limit) {
			BLOCKED.computeIfAbsent(source, key -> new long[1])[0]++;
			LAST_BLOCKED.put(source, new Blocked(level.dimension(), pos.immutable(), id, limit));
			return false;
		}
		count[0]++;
		return true;
	}

	/** True while a breeding goal that already asked the limit makes the baby, so it is not asked twice. */
	private static boolean breedingChecked;

	/**
	 * Breeding of two animals, checked for the parent's type. When refused, both lose their love mode,
	 * so they can be fed again later. Counted right away when allowed, like {@link #allows}.
	 */
	public static boolean allowsBreeding(Animal parent, Animal partner) {
		if (!(parent.level() instanceof ServerLevel level)
			|| allows(level, parent.getType(), parent.blockPosition(), Source.BREEDING)) {
			return true;
		}
		parent.resetLove();
		if (partner != null) {
			partner.resetLove();
		}
		return false;
	}

	/** Set by the breeding goals around making the baby; see {@link #breedingChecked()}. */
	public static void setBreedingChecked(boolean checked) {
		breedingChecked = checked;
	}

	/** Whether the breeding happening now was already checked by its goal. */
	public static boolean breedingChecked() {
		return breedingChecked;
	}

	/** Whether the config lets the limit stop this source. */
	public static boolean limits(Source source) {
		return switch (source) {
			case NATURAL -> TickEngineConfig.limitNaturalSpawning;
			case SPAWNER -> TickEngineConfig.limitSpawners;
			case TRIAL_SPAWNER -> TickEngineConfig.limitTrialSpawners;
			case BREEDING -> TickEngineConfig.limitBreeding;
		};
	}

	private static int[] count(ServerLevel level, EntityType<?> type, int chunkX, int chunkZ) {
		int tick = level.getServer().getTickCount();
		// A changed rule or limit must not wait for counts taken under the old one; they are cheap to retake.
		if (tick != countsTick || TickEngineConfig.version != cachedVersion) {
			COUNTS.clear();
			countsTick = tick;
			cachedVersion = TickEngineConfig.version;
		}
		Key key = new Key(level.dimension(), chunkX, chunkZ, type);
		int[] count = COUNTS.get(key);
		if (count == null) {
			// The whole height of the chunk. Only loaded entities count, like the mob cap.
			int minX = chunkX << 4;
			int minZ = chunkZ << 4;
			AABB chunk = new AABB(minX, MinecraftCompat.minY(level), minZ, minX + 16, MinecraftCompat.maxY(level) + 1, minZ + 16);
			count = new int[] {level.getEntities(type, chunk, Entity::isAlive).size()};
			COUNTS.put(key, count);
		}
		return count;
	}

	public static long blocked(Source source) {
		long[] blocked = BLOCKED.get(source);
		return blocked == null ? 0 : blocked[0];
	}

	/** Null when the source was not stopped since the server started. */
	public static Blocked lastBlocked(Source source) {
		return LAST_BLOCKED.get(source);
	}

	public static void reset() {
		COUNTS.clear();
		BLOCKED.clear();
		LAST_BLOCKED.clear();
		countsTick = Integer.MIN_VALUE;
		breedingChecked = false;
	}
}
